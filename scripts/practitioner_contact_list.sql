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
-- has since moved, the export carries the new owner. Query 2 exports the
-- usernames we hold so the fetch script can report which practitioners that
-- leaves unreachable.
--
-- READ ONLY, and enforced rather than promised: the script is two SELECTs and
-- creates nothing, not even a temporary table, and it runs inside a READ ONLY
-- transaction, so Postgres itself refuses any write -- CREATE TEMP TABLE
-- included -- should an edit ever introduce one. run_practitioner_export.sh
-- also opens the whole session with default_transaction_read_only=on and checks
-- the server honoured it before running this. The output files are written by
-- the psql CLIENT (\o), not the server.
--
-- The summary reports (CRNs by status, by region and PDU, usernames that own
-- no case, usernames that look like service accounts) are worked out locally
-- from the two output files by run_practitioner_export.sh, not queried here.
--
-- Normally run via scripts/run_practitioner_export.sh, which does every step
-- including the port-forward. To run it by hand:
--
-- Usage -- run it from a working directory OUTSIDE the repo, because the files
-- it writes are personal data and psql writes them wherever it was started:
--   umask 077   # the outputs are personal data: keep them private to you
--   mkdir -p ~/esup-practitioner-export && cd ~/esup-practitioner-export
--   PGOPTIONS='-c default_transaction_read_only=on' \
--     psql -h 127.0.0.1 -p 5432 -f ~/dev/hmpps-esupervision-api/scripts/practitioner_contact_list.sql
-- (against a Cloud Platform port-forward pod; credentials from the
-- hmpps-esupervision-rds-settings secret). The repo's .gitignore also carries
-- these filenames, in case someone runs it from the checkout anyway.
--
-- Outputs (written to psql's working directory):
--   practitioner_crns.jsonl    - one object per CRN: the CRN, the username we
--                                hold, and the PDU/region snapshot. Feeds
--                                scripts/fetch_practitioner_details.sh.
--                                JSONL rather than CSV because PDU and region
--                                descriptions contain commas.
--   practitioner_usernames.csv - every distinct username we have ever recorded
--                                against a check-in, with the tables it came
--                                from. Feeds the local reports, and the wider
--                                reconciliation (see USERNAMES= in the fetch
--                                script).
--
-- NOTE: the end product is a list of named staff and their work emails --
-- personal data. Keep every file produced here outside the repo and delete
-- working copies when done.
-- ============================================================================

\set ON_ERROR_STOP on

-- One transaction for both queries: READ ONLY so the server rejects any write,
-- REPEATABLE READ so both files come from the same snapshot and agree with
-- each other even while check-ins are being written.
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;

-- ============================================================================
-- QUERY 1: The CRN list, with PDU and region
-- ============================================================================
-- Every CRN ever set up for check-ins, whatever its state now. The brief is
-- "active now or in the past", so there is deliberately no status filter:
-- INITIAL (set up, not yet verified), VERIFIED (live) and INACTIVE
-- (deactivated) all count.
--
-- offender_setup_v2 needs no separate pass: it has a FK to offender_v2, so a
-- setup can never exist without an offender row. Its practitioner_id can still
-- differ from the offender's, which is why query 2 reads it.
--
-- PDU and region: the most recent audit row that carries each. Rows written
-- when the NDelius lookup failed have all three levels null (see
-- EventAuditService.buildAudit), so they are skipped rather than taken as the
-- latest word. PDU and provider are resolved independently rather than from
-- one "latest row with either": OrganizationalUnit.description is nullable
-- while its code is not (Dtos.kt), so NDelius can return a PDU with no
-- description, and taking that row wholesale would blank a PDU we already knew
-- from an older row. Each column keeps the newest value it actually has. Rows
-- with the same timestamp are broken by id, the later insert winning, so the
-- same data always gives the same answer.
--
-- JSONL, not CSV: PDU and region descriptions can contain commas, and the
-- fetch script should not have to parse quoted CSV in bash.

\pset format unaligned
\pset tuples_only on
\o practitioner_crns.jsonl

WITH pdu AS (
  SELECT DISTINCT ON (crn) crn, pdu_code, pdu_description, occurred_at AS pdu_at
  FROM event_audit_log_v2
  WHERE pdu_description IS NOT NULL
  ORDER BY crn, occurred_at DESC, id DESC
), provider AS (
  SELECT DISTINCT ON (crn) crn, provider_code, provider_description, occurred_at AS provider_at
  FROM event_audit_log_v2
  WHERE provider_description IS NOT NULL
  ORDER BY crn, occurred_at DESC, id DESC
), geography AS (
  SELECT coalesce(p.crn, v.crn)           AS crn,
         p.pdu_code,
         p.pdu_description,
         v.provider_code,
         v.provider_description,
         -- GREATEST ignores nulls, so this is the newer of whichever sides exist.
         greatest(p.pdu_at, v.provider_at) AS snapshot_at
  FROM pdu p
  FULL JOIN provider v ON v.crn = p.crn
)
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
LEFT JOIN geography g ON g.crn = o.crn
ORDER BY o.crn;

\o

-- ============================================================================
-- QUERY 2: Every username we have ever recorded against a check-in
-- ============================================================================
-- Wider than query 1 on purpose. A practitioner who only ever reviewed someone
-- else's check-in, or who set a case up that has since been reallocated, owns
-- no row in offender_v2.practitioner_id but is still "a practitioner who had a
-- CRN on online check-ins" under a generous reading of the request.
--
-- Usernames are compared case-insensitively throughout: they arrive from the
-- UI's logged-in session and the casing is not guaranteed consistent between
-- tables.
--
-- psql's csv output format writes the header and quotes as needed, and unlike
-- \copy it takes a multi-line query.

\pset format csv
\pset tuples_only off
\o practitioner_usernames.csv

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
GROUP BY username
ORDER BY username;

\o
\pset format aligned

-- Nothing was written, so there is nothing to commit.
ROLLBACK;
