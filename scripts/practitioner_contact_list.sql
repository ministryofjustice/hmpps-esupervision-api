-- ============================================================================
-- Practitioner contact list -- CRN and username extract
-- ============================================================================
-- We have been asked for a list of every practitioner who has, or has ever
-- had, a CRN set up for online check-ins: email address and first name.
--
-- We do not store either. What we store is the NDelius username
-- (offender_v2.practitioner_id, e.g. BARRY.WHITE). Email and forename come
-- from NDelius, and the only lookup we have is BY CRN, not by username:
-- GET /v2/offenders/crn/{crn}/practitioner-details (OffenderResource.kt),
-- which proxies NDelius GET /case/{crn} and returns the practitioner's
-- forename, surname, email, staff code and username.
--
-- So this script produces the CRN list, and scripts/fetch_practitioner_details.sh
-- turns it into the CSV.
--
-- IMPORTANT -- what that lookup can and cannot tell us. NDelius returns the
-- practitioner allocated to the CRN *today*. We never sync reallocations back
-- into offender_v2, so practitioner_id is whoever set the check-in up. Where a
-- case has since moved, the fetch returns the new owner and the original
-- practitioner is missed. That is why step 2 exports the usernames we hold:
-- the fetch script diffs them against the usernames NDelius returns and writes
-- practitioners_unmatched.csv, the practitioners we cannot reach this way.
-- Getting emails for those needs a username lookup we do not have (our NDelius
-- client exposes only GET /user/{username}/alerts) -- either from the
-- esupervision-and-delius team or from HMPPS Manage Users.
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
--   practitioner_crns.csv      - crn + the username we hold. Feeds
--                                scripts/fetch_practitioner_details.sh.
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
-- persist. The output files are written by the psql CLIENT (\copy), not the
-- server, so they survive the rollback -- which is what we want.
--
-- Not "SET TRANSACTION READ ONLY": that would block CREATE TEMP TABLE.
BEGIN;

-- ============================================================================
-- STEP 1: The CRN list
-- ============================================================================
-- Every CRN ever set up for check-ins, whatever its state now. The brief is
-- "active now or in the past", so there is deliberately no status filter:
-- INITIAL (set up, not yet verified), VERIFIED (live) and INACTIVE
-- (deactivated) all count.
--
-- offender_setup_v2 needs no separate pass: it has a FK to offender_v2, so a
-- setup can never exist without an offender row. Its practitioner_id can still
-- differ from the offender's, which is why step 2 reads it.

\copy (SELECT o.crn, upper(o.practitioner_id) AS stored_username, o.status, to_char(o.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS registered_on FROM offender_v2 o ORDER BY o.crn) to 'practitioner_crns.csv' with (format csv, header true)

-- How big is the job, and how much of it is historic?
SELECT status, count(*) AS crns, count(DISTINCT upper(practitioner_id)) AS distinct_usernames
FROM offender_v2
GROUP BY ROLLUP (status)
ORDER BY status NULLS LAST;

-- ============================================================================
-- STEP 2: Every username we have ever recorded against a check-in
-- ============================================================================
-- Wider than step 1 on purpose. A practitioner who only ever reviewed someone
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

-- How much wider than step 1 is it?
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
