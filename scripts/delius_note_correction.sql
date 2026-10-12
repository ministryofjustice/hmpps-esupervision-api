-- ============================================================================
-- Delius check-in note correction — contact matching (ESUP-1956 follow-up)
-- ============================================================================
-- A race condition (fixed in 8eefb1a, 2026-05-22) published checkin-submitted
-- domain events before the transaction committed, so NDelius sometimes fetched
-- the contact note before survey_response was visible. Those contacts carry the
-- "Check in status: Submitted" text but no answers.
--
-- The bug was intermittent, so we cannot identify the affected contacts
-- ourselves. NDelius ran a query for contacts with the submitted text and no
-- answers text; THEIR LIST IS THE SOURCE OF TRUTH. This query joins that list
-- to our check-ins so we can hand back the correct answers per contact.
--
-- READ ONLY, and runs fine on a read-only connection: one SELECT, no temp
-- tables. The contact list is passed in as data rather than loaded into the
-- database, and everything that needs judging (match grades, exception
-- reports) is worked out afterwards, offline, from the JSONL this writes.
--
-- Normally run for you, end to end, by
--   ./scripts/run_delius_note_correction.sh delius_contacts.csv
-- which also converts the CSV and builds the reports. By hand, from the
-- directory holding contacts.json (scripts/delius_note_correction.py prepare):
--   PGOPTIONS='-c default_transaction_read_only=on' \
--     psql -X -f scripts/delius_note_correction.sql
--
-- Input:  contacts.json, read by psql on the CLIENT (never sent anywhere but
--         as a query parameter): [{"row_no":1,"crn":"E659305",
--         "contact_date":"22/04/2026","start_time":"09:15:25",
--         "contact_id":"1996227159","soft_deleted":"0"}, ...]
-- Output: matched.jsonl, one line per contact, in row order. Every contact
--         appears, matched or not.
--
-- NOTE: answer text is sensitive personal data; treat any file produced here
-- accordingly and delete working copies when done.
-- ============================================================================

\set ON_ERROR_STOP on

-- Belt and braces on top of default_transaction_read_only: this transaction
-- cannot write even if the connection was opened without it.
BEGIN READ ONLY;
SET LOCAL statement_timeout = '5min';

\set contacts `cat contacts.json`

\pset format unaligned
\pset tuples_only on
\o matched.jsonl

WITH input AS (
  SELECT *
  FROM json_to_recordset(:'contacts'::json) AS t(
    row_no bigint, crn text, contact_date text, start_time text,
    contact_id text, soft_deleted text
  )
),
-- Parsed explicitly: DD/MM/YYYY is ambiguous under the default datestyle, and
-- to_timestamp(text,text) would resolve against the session time zone.
-- date + time -> timestamp -> AT TIME ZONE is deterministic. The list was
-- supplied as DD/MM/YYYY, but a re-export (or a trip through Excel) yields
-- YYYY-MM-DD; anything else -- including a blank or malformed START_TIME --
-- parses to NULL rather than the wrong date or a failed run, and is reported
-- as unparsed.
contacts AS (
  SELECT
    row_no,
    upper(trim(crn))   AS crn,
    trim(contact_id)   AS contact_id,
    trim(contact_date) AS contact_date_raw,
    trim(start_time)   AS start_time_raw,
    trim(soft_deleted) AS soft_deleted,
    (CASE
       WHEN trim(contact_date) ~ '^\d{4}-\d{1,2}-\d{1,2}$' THEN to_date(trim(contact_date), 'YYYY-MM-DD')
       WHEN trim(contact_date) ~ '^\d{1,2}/\d{1,2}/\d{4}$' THEN to_date(trim(contact_date), 'DD/MM/YYYY')
     END + CASE WHEN trim(start_time) ~ '^\d{1,2}:\d{2}(:\d{2}(\.\d+)?)?$' THEN trim(start_time)::time END)
      AT TIME ZONE 'Europe/London' AS contact_at
  FROM input
)
-- The event was published within seconds of submitted_at (publishing before
-- commit was the bug), so contact_at should land almost exactly on it. Match
-- the nearest submission within +/- 1 day; the report grades the match so
-- anything that is not a near-exact hit gets looked at by hand.
SELECT json_build_object(
         'row_no',            d.row_no,
         'crn',               d.crn,
         'contact_id',        d.contact_id,
         'contactDateRaw',    d.contact_date_raw,
         'startTimeRaw',      d.start_time_raw,
         'softDeleted',       d.soft_deleted,
         'contactAt',         to_char(d.contact_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
         'offenderFound',     o.id IS NOT NULL,
         'checkinUuid',       c.uuid,
         'submittedAt',       to_char(c.submitted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
         'status',            c.status,
         'sensitive',         c.sensitive,
         'deltaSecs',         abs(extract(epoch FROM (c.submitted_at - d.contact_at))),
         -- every submission in the window, so a nearest-match choice between
         -- several can be checked by hand
         'candidateTimes',    w.times,
         -- the audit log records its own occurred_at per submitted event, as an
         -- independent cross-check of the submitted_at-based match
         'auditDeltaSecs',    a.deltas,
         'surveyResponse',    c.survey_response
       )::text
FROM contacts d
LEFT JOIN offender_v2 o ON o.crn = d.crn
LEFT JOIN LATERAL (
  SELECT ck.uuid, ck.submitted_at, ck.status, ck.sensitive, ck.survey_response
  FROM offender_checkin_v2 ck
  WHERE ck.offender_id = o.id
    AND ck.submitted_at IS NOT NULL
    AND ck.submitted_at BETWEEN d.contact_at - interval '1 day'
                            AND d.contact_at + interval '1 day'
  ORDER BY abs(extract(epoch FROM (ck.submitted_at - d.contact_at)))
  LIMIT 1
) c ON true
LEFT JOIN LATERAL (
  SELECT json_agg(to_char(ck.submitted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
                  ORDER BY ck.submitted_at) AS times
  FROM offender_checkin_v2 ck
  WHERE ck.offender_id = o.id
    AND ck.submitted_at IS NOT NULL
    AND ck.submitted_at BETWEEN d.contact_at - interval '1 day'
                            AND d.contact_at + interval '1 day'
) w ON true
LEFT JOIN LATERAL (
  SELECT json_agg(abs(extract(epoch FROM (e.occurred_at - d.contact_at))) ORDER BY e.occurred_at) AS deltas
  FROM event_audit_log_v2 e
  WHERE e.checkin_uuid = c.uuid
    AND e.event_type = 'CHECKIN_SUBMITTED'
) a ON true
ORDER BY d.row_no;

\o
\pset tuples_only off
\pset format aligned

ROLLBACK;
