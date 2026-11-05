-- Deletes every trace of the people on probation whose CRNs are listed in
-- orphan_crns.txt (one CRN per line, read relative to psql's cwd). Those are
-- CRNs that NDelius no longer knows, mostly left behind by e2e tests that
-- create a case in NDelius and delete it afterwards.
--
-- Run by scripts/run_orphan_crn_cleanup.sh, which builds orphan_crns.txt.
-- DEV ONLY. It must never run against test, preprod or prod.
--
-- psql variables:
--   target_env   must be dev, or nothing runs
--   expected_addr  the IP the dev RDS hostname resolves to; nothing runs unless
--                inet_server_addr() -- the server's own address, which no
--                connection setting can fake -- is exactly this
--   apply  true to COMMIT, false (the default) to roll back, so the counts
--          printed are exactly what an apply would delete. Any other value is
--          an error, and nothing is committed.
--
-- Everything runs in one transaction: either all of a CRN's rows go or none
-- do. Two guards raise before anything is deleted if the schema has moved on
-- since this was written: a new foreign key into any table deleted from, or a
-- new outbox item type (outbox_items.entity_id has no foreign key, so only the
-- type says what it points at). Update the deletes and the guard lists.
--
-- Left alone on purpose:
--   question_list            lists are shared, not owned by one offender
--   monthly_stats and the
--   stats materialised views recomputed from event_audit_log_v2 on refresh
--   v1 / migration tables    offender_crn_mapping, offender_id_mapping,
--                            migration_control -- legacy, a handful of rows
--   S3                       photos and videos under the offender and
--                            check-in UUIDs (written to orphan_uuids.csv)

\set ON_ERROR_STOP 1
\if :{?apply}
\else
  \set apply false
\endif

-- Dev only: refuse before the transaction opens unless the caller named dev
-- and we are connected to the database the dev secret names.
\if :{?target_env}
\else
  \set target_env ''
\endif
\if :{?expected_addr}
\else
  \set expected_addr ''
\endif
SELECT :'target_env' = 'dev' AND :'expected_addr' <> ''
   AND coalesce(host(inet_server_addr()), '') = :'expected_addr' AS is_dev \gset
\if :is_dev
\else
  \echo 'Refusing: this script runs against dev only (pass -v target_env=dev -v expected_addr=<IP the dev RDS hostname resolves to>).'
  DO $$ BEGIN RAISE EXCEPTION 'not the dev database -- nothing was changed'; END $$;
\endif

BEGIN;

CREATE TEMP TABLE orphan_crn (crn text PRIMARY KEY) ON COMMIT DROP;
\copy orphan_crn (crn) from 'orphan_crns.txt'

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM orphan_crn WHERE crn !~ '^[A-Z][0-9]{6}$') THEN
    RAISE EXCEPTION 'orphan_crns.txt holds something that is not a CRN';
  END IF;
END $$;

-- Lock these people and their check-ins first, so the app cannot add a
-- check-in, change a status (whose trigger writes outbox_items) or record an
-- event for them between the snapshots below and the deletes. outbox_items and
-- checkin_delius_event_v2 have no foreign keys, so such rows would otherwise be
-- left pointing at nothing.
DO $$
BEGIN
  PERFORM 1 FROM offender_v2 WHERE crn IN (SELECT crn FROM orphan_crn) FOR UPDATE;
  PERFORM 1 FROM offender_checkin_v2
    WHERE offender_id IN (SELECT id FROM offender_v2 WHERE crn IN (SELECT crn FROM orphan_crn))
    FOR UPDATE;
END $$;

CREATE TEMP TABLE o ON COMMIT DROP AS
  SELECT ov.id, ov.uuid, ov.crn FROM offender_v2 ov JOIN orphan_crn USING (crn);
CREATE TEMP TABLE c ON COMMIT DROP AS
  SELECT ch.id, ch.uuid FROM offender_checkin_v2 ch WHERE ch.offender_id IN (SELECT id FROM o);
CREATE TEMP TABLE s ON COMMIT DROP AS
  SELECT st.id FROM offender_setup_v2 st WHERE st.offender_id IN (SELECT id FROM o);
CREATE TEMP TABLE el ON COMMIT DROP AS
  SELECT e.id FROM offender_event_log_v2 e
  WHERE e.offender_id IN (SELECT id FROM o) OR e.checkin IN (SELECT id FROM c);

-- Every foreign key into a table we delete from must come from a table we
-- also delete from, or the deletes below would miss rows: fail on the
-- constraint, or with ON DELETE CASCADE take rows the counts never show.
DO $$
DECLARE
  deleted_from text[] := ARRAY[
    'offender_v2', 'offender_checkin_v2', 'offender_setup_v2', 'offender_event_log_v2',
    'generic_notification_v2', 'setup_event_backfill_v2', 'question_list_assignment',
    'outbox_items', 'checkin_delius_event_v2', 'checkin_note_resend',
    'migration_events_to_send', 'event_audit_log_v2'];
  unhandled text;
BEGIN
  SELECT string_agg(DISTINCT format('%s -> %s', conrelid::regclass, confrelid::regclass), ', ')
    INTO unhandled
  FROM pg_constraint
  WHERE contype = 'f'
    AND confrelid::regclass::text = ANY (deleted_from)
    AND NOT conrelid::regclass::text = ANY (deleted_from);
  IF unhandled IS NOT NULL THEN
    RAISE EXCEPTION 'foreign keys this script does not handle: % -- update scripts/orphan_crn_cleanup.sql', unhandled;
  END IF;
END $$;

-- Every outbox item type must be one the outbox delete below maps to its entity.
DO $$
DECLARE
  unhandled text;
BEGIN
  SELECT string_agg(t::text, ', ') INTO unhandled
  FROM unnest(enum_range(NULL::outboxitemtype)) AS t
  WHERE t::text NOT IN ('CHECKIN_CREATED', 'CHECKIN_SUBMITTED', 'CHECKIN_REVIEWED', 'CHECKIN_EXPIRED',
                        'CHECKIN_ANNOTATED',
                        'OFFENDER_SETUP_COMPLETE', 'OFFENDER_DEACTIVATED', 'OFFENDER_REACTIVATED');
  IF unhandled IS NOT NULL THEN
    RAISE EXCEPTION 'outbox item types this script does not handle: % -- update scripts/orphan_crn_cleanup.sql', unhandled;
  END IF;
END $$;

\echo
\echo 'Orphan CRNs listed, and how many of them are in offender_v2:'
SELECT (SELECT count(*) FROM orphan_crn) AS listed, (SELECT count(*) FROM o) AS in_offender_v2;

-- For clearing S3 by hand, if wanted.
\copy (SELECT 'offender' AS kind, o.crn, o.uuid FROM o UNION ALL SELECT 'checkin', o.crn, ch.uuid FROM offender_checkin_v2 ch JOIN o ON o.id = ch.offender_id ORDER BY 2, 1) to 'orphan_uuids.csv' with csv header

CREATE TEMP TABLE deleted (tbl text, n bigint, seq serial) ON COMMIT DROP;

-- outbox_items.entity_id has no foreign key; what it points at depends on type.
WITH d AS (
  DELETE FROM outbox_items
  WHERE (type::text IN ('CHECKIN_CREATED', 'CHECKIN_SUBMITTED', 'CHECKIN_REVIEWED', 'CHECKIN_EXPIRED')
         AND entity_id IN (SELECT id FROM c))
     OR (type::text = 'CHECKIN_ANNOTATED' AND entity_id IN (SELECT id FROM el))
     OR (type::text IN ('OFFENDER_SETUP_COMPLETE', 'OFFENDER_DEACTIVATED', 'OFFENDER_REACTIVATED')
         AND entity_id IN (SELECT id FROM s))
  RETURNING 1)
INSERT INTO deleted (tbl, n) SELECT 'outbox_items', count(*) FROM d;

WITH d AS (
  DELETE FROM checkin_delius_event_v2
  WHERE crn IN (SELECT crn FROM orphan_crn) OR checkin_uuid IN (SELECT uuid FROM c)
  RETURNING 1)
INSERT INTO deleted (tbl, n) SELECT 'checkin_delius_event_v2', count(*) FROM d;

WITH d AS (DELETE FROM checkin_note_resend WHERE checkin IN (SELECT uuid FROM c) RETURNING 1)
INSERT INTO deleted (tbl, n) SELECT 'checkin_note_resend', count(*) FROM d;

WITH d AS (DELETE FROM migration_events_to_send WHERE checkin IN (SELECT uuid FROM c) RETURNING 1)
INSERT INTO deleted (tbl, n) SELECT 'migration_events_to_send', count(*) FROM d;

WITH d AS (
  DELETE FROM question_list_assignment
  WHERE offender_id IN (SELECT id FROM o) OR checkin_id IN (SELECT id FROM c)
  RETURNING 1)
INSERT INTO deleted (tbl, n) SELECT 'question_list_assignment', count(*) FROM d;

WITH d AS (DELETE FROM offender_event_log_v2 WHERE id IN (SELECT id FROM el) RETURNING 1)
INSERT INTO deleted (tbl, n) SELECT 'offender_event_log_v2', count(*) FROM d;

WITH d AS (DELETE FROM generic_notification_v2 WHERE offender_id IN (SELECT id FROM o) RETURNING 1)
INSERT INTO deleted (tbl, n) SELECT 'generic_notification_v2', count(*) FROM d;

WITH d AS (DELETE FROM setup_event_backfill_v2 WHERE offender_id IN (SELECT id FROM o) RETURNING 1)
INSERT INTO deleted (tbl, n) SELECT 'setup_event_backfill_v2', count(*) FROM d;

WITH d AS (DELETE FROM offender_setup_v2 WHERE id IN (SELECT id FROM s) RETURNING 1)
INSERT INTO deleted (tbl, n) SELECT 'offender_setup_v2', count(*) FROM d;

WITH d AS (DELETE FROM offender_checkin_v2 WHERE id IN (SELECT id FROM c) RETURNING 1)
INSERT INTO deleted (tbl, n) SELECT 'offender_checkin_v2', count(*) FROM d;

WITH d AS (DELETE FROM event_audit_log_v2 WHERE crn IN (SELECT crn FROM orphan_crn) RETURNING 1)
INSERT INTO deleted (tbl, n) SELECT 'event_audit_log_v2', count(*) FROM d;

WITH d AS (DELETE FROM offender_v2 WHERE id IN (SELECT id FROM o) RETURNING 1)
INSERT INTO deleted (tbl, n) SELECT 'offender_v2', count(*) FROM d;

\echo
\if :apply
  \echo 'Rows deleted (COMMITTING):'
\else
  \echo 'Rows that would be deleted (dry run, ROLLING BACK):'
\endif
SELECT tbl AS "table", n AS rows FROM deleted ORDER BY seq;

\if :apply
  COMMIT;
\else
  ROLLBACK;
\endif
