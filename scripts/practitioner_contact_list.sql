-- ============================================================================
-- Practitioner contact list -- CRN, geography and username extract
-- ============================================================================
-- We have been asked for a mailing list of practitioners: one row each,
-- carrying PDU, region, CRN, POP count, email address. The cohort is every CRN
-- that has, or has ever had, an online check-in; this script exports those
-- CRNs and scripts/fetch_practitioner_details.sh collapses them onto the
-- practitioner who holds them.
--
-- Where each column comes from:
--   CRN           offender_v2 (this script)
--   PDU, region   NDelius, via the snapshot in event_audit_log_v2 (this
--                 script). "Region" is NDelius's provider -- the probation
--                 region -- which EventAuditService records as
--                 provider_code/provider_description alongside the PDU.
--   email address NDelius, by CRN, via
--                 GET /v2/offenders/crn/{crn}/practitioner-details
--                 (scripts/fetch_practitioner_details.sh)
--   POP count     derived: how many of the export's CRNs the practitioner
--                 holds (computed by the fetch script, once the current
--                 practitioner for each CRN is known)
--
-- Every CRN in the cohort reaches the export, including the ones NDelius holds
-- no email for: those rows carry a blank address, to be filled in by hand from
-- practitioners_unmatched.csv, which names the username behind each.
--
-- We store no practitioner email or name, only the NDelius username
-- (offender_v2.practitioner_id, e.g. BARRY.WHITE), and the only lookup we have
-- is BY CRN, not by username -- hence the two-step shape of this job.
--
-- WHY THE SNAPSHOT FOR PDU AND REGION. The practitioner-details endpoint
-- returns the PDU but not the provider, so the region cannot come from it at
-- all. event_audit_log_v2 has both: EventAuditService stamps every setup and
-- check-in event with the practitioner's LAU, PDU and provider as they were at
-- the time. The fetch script prefers the endpoint's live PDU where it has one
-- and falls back to the snapshot below, which is also the only source for
-- region; it records which source each row used.
--
-- IMPORTANT -- who the email belongs to. NDelius returns the practitioner
-- allocated to the CRN *today*. We never sync reallocations back into
-- offender_v2, so practitioner_id is whoever set the check-in up. Where a case
-- has since moved, the export carries the new owner. Step 2 exports the
-- usernames we hold so the fetch script can report which practitioners that
-- leaves unreachable.
--
-- READ ONLY: this script creates temp tables only. It does not write to any
-- application table.
--
-- Usage:
--   psql -h 127.0.0.1 -p 5432 -f scripts/practitioner_contact_list.sql
-- (against a Cloud Platform port-forward pod; credentials from the
-- hmpps-esupervision-rds-settings secret)
--
-- Outputs (written to psql's working directory, NOT the repo):
--   practitioner_crns.jsonl    - one object per CRN: the CRN, the username we
--                                hold, and the PDU/region snapshot. Feeds
--                                scripts/fetch_practitioner_details.sh.
--                                JSONL rather than CSV because PDU and region
--                                descriptions contain commas.
--   practitioner_usernames.csv - every distinct username we have ever recorded
--                                against a check-in, with the tables it came
--                                from. Only needed for the wider reconciliation
--                                (see USERNAMES= in the fetch script).
--   plus the exception reports printed to the terminal.
--
-- NOTE: the end product is a list of named staff and their work emails --
-- personal data. Keep every file produced here outside the repo and delete
-- working copies when done.
-- ============================================================================

\set ON_ERROR_STOP on
\timing on

-- Belt and braces, as in scripts/delius_note_correction.sql: every CREATE
-- below is a TEMP table, the application tables appear only in FROM, and the
-- transaction always ends in ROLLBACK, so nothing this session does can
-- persist. The output files are written by the psql CLIENT (\copy and \o), not
-- the server, so they survive the rollback -- which is what we want.
--
-- Not "SET TRANSACTION READ ONLY": that would block CREATE TEMP TABLE.
BEGIN;

-- ============================================================================
-- STEP 1: PDU and region per CRN
-- ============================================================================
-- The most recent audit row that carries a geography. Rows written when the
-- NDelius lookup failed have all three levels null (see
-- EventAuditService.buildAudit), so they are skipped rather than taken as the
-- latest word. PDU and provider are written from the same practitioner record
-- in the same statement, so a row gives a consistent pair.

DROP TABLE IF EXISTS pg_temp.crn_geography;
CREATE TEMP TABLE crn_geography AS
SELECT DISTINCT ON (crn)
       crn,
       pdu_code,
       pdu_description,
       provider_code,
       provider_description,
       occurred_at AS snapshot_at
FROM event_audit_log_v2
WHERE pdu_description IS NOT NULL
   OR provider_description IS NOT NULL
ORDER BY crn, occurred_at DESC;

-- ============================================================================
-- STEP 2: The CRN list
-- ============================================================================
-- Every CRN ever set up for check-ins, whatever its state now. The brief is
-- "active now or in the past", so there is deliberately no status filter:
-- INITIAL (set up, not yet verified), VERIFIED (live) and INACTIVE
-- (deactivated) all count.
--
-- offender_setup_v2 needs no separate pass: it has a FK to offender_v2, so a
-- setup can never exist without an offender row. Its practitioner_id can still
-- differ from the offender's, which is why step 3 reads it.
--
-- JSONL, not CSV: PDU and region descriptions can contain commas, and the
-- fetch script should not have to parse quoted CSV in bash. \o rather than
-- \copy for the same reason \copy is wrong for JSON -- csv format would quote
-- the whole line and text format would backslash-escape it.

\pset format unaligned
\pset tuples_only on
\o practitioner_crns.jsonl

SELECT json_build_object(
         'crn',            o.crn,
         'storedUsername', upper(o.practitioner_id),
         'status',         o.status,
         'registeredOn',   to_char(o.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD'),
         'pdu',            g.pdu_description,
         'pduCode',        g.pdu_code,
         'region',         g.provider_description,
         'regionCode',     g.provider_code,
         'snapshotAt',     to_char(g.snapshot_at AT TIME ZONE 'UTC', 'YYYY-MM-DD')
       )::text
FROM offender_v2 o
LEFT JOIN crn_geography g ON g.crn = o.crn
ORDER BY o.crn;

\o
\pset tuples_only off
\pset format aligned

-- How big is the job, how much of it is historic, and how much of it has a
-- geography? CRNs with no snapshot come out with a blank region: the fetch can
-- fill their PDU from the live endpoint but has nowhere to get the region.
SELECT o.status,
       count(*)                                                    AS crns,
       count(DISTINCT upper(o.practitioner_id))                    AS distinct_usernames,
       count(g.crn)                                                AS with_geography,
       count(*) FILTER (WHERE g.provider_description IS NULL)      AS no_region
FROM offender_v2 o
LEFT JOIN crn_geography g ON g.crn = o.crn
GROUP BY ROLLUP (o.status)
ORDER BY o.status NULLS LAST;

-- The regions and PDUs the export will report, and how many CRNs sit in each.
-- Eyeball this before handing anything over: a region that looks far too small
-- usually means stale snapshots rather than a real distribution.
SELECT coalesce(g.provider_description, '(none)') AS region,
       coalesce(g.pdu_description, '(none)')      AS pdu,
       count(*)                                   AS crns
FROM offender_v2 o
LEFT JOIN crn_geography g ON g.crn = o.crn
GROUP BY 1, 2
ORDER BY 1, 3 DESC;

-- ============================================================================
-- STEP 3: Every username we have ever recorded against a check-in
-- ============================================================================
-- Wider than step 2 on purpose. A practitioner who only ever reviewed someone
-- else's check-in, or who set a case up that has since been reallocated, owns
-- no row in offender_v2.practitioner_id but is still "a practitioner who had a
-- CRN on online check-ins" under a generous reading of the request.
--
-- Usernames are compared case-insensitively throughout: they arrive from the
-- UI's logged-in session and the casing is not guaranteed consistent between
-- tables.

DROP TABLE IF EXISTS pg_temp.practitioner_usernames;
CREATE TEMP TABLE practitioner_usernames AS
WITH sources AS (
  SELECT upper(practitioner_id) AS username, 'offender_v2'           AS source FROM offender_v2
  UNION ALL
  SELECT upper(practitioner_id),             'offender_setup_v2'              FROM offender_setup_v2
  UNION ALL
  SELECT upper(created_by),                  'checkin_created_by'             FROM offender_checkin_v2
  UNION ALL
  SELECT upper(reviewed_by),                 'checkin_reviewed_by'            FROM offender_checkin_v2
  UNION ALL
  SELECT upper(review_started_by),           'checkin_review_started_by'      FROM offender_checkin_v2
  UNION ALL
  SELECT upper(practitioner),                'offender_event_log_v2'          FROM offender_event_log_v2
  UNION ALL
  SELECT upper(practitioner_id),             'event_audit_log_v2'             FROM event_audit_log_v2
)
SELECT username,
       count(*)                                          AS mentions,
       string_agg(DISTINCT source, '|' ORDER BY source)  AS sources
FROM sources
WHERE username IS NOT NULL
  AND btrim(username) <> ''
  -- Not people: the scheduled jobs write SYSTEM, and a client-credentials
  -- token authenticates as its client id rather than a user.
  AND username NOT IN ('SYSTEM', 'AUTH_USER', 'ESUPERVISION_API')
GROUP BY username;

\copy (SELECT username, mentions, sources FROM practitioner_usernames ORDER BY username) to 'practitioner_usernames.csv' with (format csv, header true)

-- How much wider than step 2 is it?
SELECT count(*) AS usernames_total,
       count(*) FILTER (WHERE sources LIKE '%offender_v2%')       AS own_a_case_now,
       count(*) FILTER (WHERE sources NOT LIKE '%offender_v2%')   AS seen_but_own_no_case
FROM practitioner_usernames;

-- The ones that own no current case: these are exactly the practitioners the
-- CRN-based fetch cannot reach, unless they happen to still hold some other
-- CRN in the list. Expect reallocations and reviewers here.
SELECT username, mentions, sources
FROM practitioner_usernames
WHERE sources NOT LIKE '%offender_v2%'
ORDER BY mentions DESC, username;

-- Anything that does not look like an NDelius FIRST.LAST username is probably
-- a service account or test data that slipped through the filter above. Check
-- before mailing anyone.
SELECT username, mentions, sources
FROM practitioner_usernames
WHERE username !~ '^[A-Z0-9''-]+\.[A-Z0-9''.-]+$'
ORDER BY username;

-- ============================================================================
-- Discard everything. See the note at the top of the file.
-- ============================================================================
ROLLBACK;
