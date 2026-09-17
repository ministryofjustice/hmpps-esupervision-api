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
-- answers text; THEIR LIST IS THE SOURCE OF TRUTH. This script joins that list
-- to our check-ins so we can hand back the correct note text per contact.
--
-- Input CSV (as supplied, with header):
--   CRN,CONTACT_DATE,START_TIME,CONTACT_ID,SOFT_DELETED
--   E659305,22/04/2026,09:15:25,1996227159,0
-- SOFT_DELETED is not used for matching; it is carried through so the file we
-- return diffs cleanly against the one we were given.
--
-- READ ONLY: this script creates temp tables only. It does not write to any
-- application table.
--
-- Usage:
--   psql -h 127.0.0.1 -p 5432 -f scripts/delius_note_correction.sql
-- (against a Cloud Platform port-forward pod; credentials from the
-- hmpps-esupervision-rds-settings secret)
--
-- Outputs (written to psql's working directory, NOT the repo):
--   checkin_export.jsonl  - feeds the offline note generator (step 4b). This is
--                           the file you want: CheckinNoteExportTool turns it
--                           into the note text.
--   checkin_export_full.jsonl
--                         - same, plus the review fields (step 4c). Use this
--                           one if NDelius has to replace the WHOLE contact
--                           note rather than amend the submission part; feed
--                           it to the generator with --full-note.
--   checkins_to_fetch.csv - only needed for the alternative route, fetching
--                           notes from GET /v2/events/checkin-submitted/{uuid}
--                           (needs a token holding ROLE_ESUPERVISION__CHECK_IN__RO).
--   plus the exception reports printed to the terminal.
--
-- NOTE: answer text is sensitive personal data; treat any file produced here
-- accordingly and delete working copies when done.
-- ============================================================================

\set ON_ERROR_STOP on
\timing on

-- Belt and braces. This script contains no write statement against any
-- application table -- every CREATE is a TEMP table, every DROP is pg_temp-
-- qualified, and the four application tables (offender_v2,
-- offender_checkin_v2, offender_event_log_v2, event_audit_log_v2) appear only
-- in FROM/JOIN. The transaction below means that even so, nothing this session
-- does to the database can persist: it always ends in ROLLBACK, and an early
-- exit under ON_ERROR_STOP rolls back on disconnect.
--
-- Note this is NOT "SET TRANSACTION READ ONLY": that would block CREATE TEMP
-- TABLE, which this script needs (a read-only transaction disallows all CREATE
-- commands, temporary or not).
--
-- The output files are written by the psql CLIENT (\copy and \o), not the
-- server, so they survive the rollback -- which is exactly what we want.
--
-- If you step through the script by hand, keep the BEGIN and run the ROLLBACK
-- at the end. Forgetting it is harmless: disconnecting rolls back too.
BEGIN;

-- ============================================================================
-- STEP 1: Load the NDelius contact list
-- ============================================================================
-- Loaded as text and parsed explicitly: DD/MM/YYYY is ambiguous under the
-- default datestyle, and to_timestamp(text,text) would resolve against the
-- session time zone. date + time -> timestamp -> AT TIME ZONE is deterministic.

DROP TABLE IF EXISTS pg_temp.delius_raw;
CREATE TEMP TABLE delius_raw (
  row_no       bigint GENERATED ALWAYS AS IDENTITY,
  crn          text,
  contact_date text,
  start_time   text,
  contact_id   text,
  soft_deleted text
);

\copy delius_raw (crn, contact_date, start_time, contact_id, soft_deleted) from 'delius_contacts.csv' with (format csv, header true)

-- The date format varies with how the file was exported: the list was supplied
-- as DD/MM/YYYY but a re-export (or a trip through Excel) yields YYYY-MM-DD.
-- Both are handled; anything else parses to NULL rather than silently becoming
-- the wrong date, and is caught by the validation query below.
DROP TABLE IF EXISTS pg_temp.delius_contacts;
CREATE TEMP TABLE delius_contacts AS
SELECT
  row_no,
  upper(trim(crn))            AS crn,
  trim(contact_id)            AS contact_id,
  trim(contact_date)          AS contact_date_raw,
  trim(start_time)            AS start_time_raw,
  trim(soft_deleted)          AS soft_deleted,
  (CASE
     WHEN trim(contact_date) ~ '^\d{4}-\d{1,2}-\d{1,2}$' THEN to_date(trim(contact_date), 'YYYY-MM-DD')
     WHEN trim(contact_date) ~ '^\d{1,2}/\d{1,2}/\d{4}$' THEN to_date(trim(contact_date), 'DD/MM/YYYY')
   END + trim(start_time)::time)
    AT TIME ZONE 'Europe/London' AS contact_at
FROM delius_raw;

CREATE INDEX ON pg_temp.delius_contacts (crn);

-- Sanity check the parse before relying on it: unparsed must be 0, and
-- latest_contact must be on or before the 2026-05-22 fix.
SELECT count(*)                AS rows_loaded,
       count(*) FILTER (WHERE contact_at IS NULL) AS unparsed,
       count(DISTINCT crn)     AS distinct_crns,
       min(contact_at)         AS earliest_contact,
       max(contact_at)         AS latest_contact,
       count(*) FILTER (WHERE soft_deleted <> '0') AS soft_deleted_rows
FROM delius_contacts;

-- Distinct raw date/time spellings, to confirm the formats seen are the ones
-- handled above.
SELECT contact_date_raw, count(*)
FROM delius_contacts GROUP BY 1 ORDER BY 1 LIMIT 20;

-- ============================================================================
-- STEP 2: Match each contact to a check-in
-- ============================================================================
-- The event was published within seconds of submitted_at (publishing before
-- commit was the bug), so contact_at should land almost exactly on it. Match
-- nearest submission within +/- 1 day, then grade the match so anything that
-- is not a near-exact hit gets looked at by hand.

DROP TABLE IF EXISTS pg_temp.matched;
CREATE TEMP TABLE matched AS
SELECT
  d.*,
  c.uuid                           AS checkin_uuid,
  c.submitted_at,
  c.status,
  c.sensitive,
  c.survey_response ->> 'version'  AS survey_version,
  (c.survey_response IS NOT NULL AND c.survey_response <> '{}'::jsonb) AS has_answers,
  abs(extract(epoch FROM (c.submitted_at - d.contact_at)))             AS delta_secs,
  CASE
    WHEN c.uuid IS NULL                                                   THEN 'UNMATCHED'
    WHEN abs(extract(epoch FROM (c.submitted_at - d.contact_at))) <= 120  THEN 'EXACT'
    WHEN abs(extract(epoch FROM (c.submitted_at - d.contact_at))) <= 3600 THEN 'CLOSE'
    ELSE 'LOOSE'
  END                              AS match_grade
FROM delius_contacts d
LEFT JOIN offender_v2 o ON o.crn = d.crn
LEFT JOIN LATERAL (
  SELECT ck.*
  FROM offender_checkin_v2 ck
  WHERE ck.offender_id = o.id
    AND ck.submitted_at IS NOT NULL
    AND ck.submitted_at BETWEEN d.contact_at - interval '1 day'
                            AND d.contact_at + interval '1 day'
  ORDER BY abs(extract(epoch FROM (ck.submitted_at - d.contact_at)))
  LIMIT 1
) c ON true;

-- ============================================================================
-- STEP 3: Exception reports — resolve all of these before handover
-- ============================================================================

-- 3a. Grade distribution. Expect overwhelmingly EXACT; anything else needs eyes.
SELECT match_grade, count(*), min(delta_secs), max(delta_secs)
FROM matched GROUP BY 1 ORDER BY 1;

-- 3b. CRNs we hold no check-in for (or no offender at all).
SELECT row_no, crn, contact_id, contact_date_raw, start_time_raw
FROM matched WHERE checkin_uuid IS NULL ORDER BY row_no;

-- 3c. Two contacts resolving to the same check-in: either a duplicate contact
--     on their side, or one of the two matches is wrong.
SELECT checkin_uuid, array_agg(contact_id ORDER BY row_no) AS contact_ids
FROM matched WHERE checkin_uuid IS NOT NULL
GROUP BY 1 HAVING count(*) > 1;

-- 3d. More than one submission inside the window. We matched on nearest, so
--     confirm the choice for these rows specifically.
SELECT d.row_no, d.crn, d.contact_id, d.contact_at, count(*) AS candidates,
       array_agg(ck.submitted_at ORDER BY ck.submitted_at) AS candidate_times
FROM delius_contacts d
JOIN offender_v2 o ON o.crn = d.crn
JOIN offender_checkin_v2 ck ON ck.offender_id = o.id
 AND ck.submitted_at IS NOT NULL
 AND ck.submitted_at BETWEEN d.contact_at - interval '1 day'
                         AND d.contact_at + interval '1 day'
GROUP BY 1, 2, 3, 4 HAVING count(*) > 1
ORDER BY d.row_no;

-- 3e. Matched, but we have no answers to give. NDelius saw the submitted text
--     with no answers, and we hold no survey_response — so these are not
--     race-condition victims. They drop out of the handover with an explanation.
SELECT row_no, crn, contact_id, checkin_uuid, submitted_at, status
FROM matched WHERE checkin_uuid IS NOT NULL AND NOT has_answers ORDER BY row_no;

-- 3f. Custom questions that the note formatter would silently drop.
--     formatSurvey only renders the customQuestions block when
--     version = '2026-04-16@questions' (v2/checkin/SurveyDetails.kt:22-36).
--     Custom questions shipped 2026-04-17, before the 2026-05-22 fix, so part
--     of this population has them. Any row here would be handed over with
--     answers missing — resolve before sending, not after.
SELECT m.row_no, m.crn, m.contact_id, m.survey_version,
       jsonb_array_length(c.survey_response -> 'customQuestions') AS n_custom
FROM matched m
JOIN offender_checkin_v2 c ON c.uuid = m.checkin_uuid
WHERE c.survey_response ? 'customQuestions'
  AND jsonb_array_length(c.survey_response -> 'customQuestions') > 0
  AND coalesce(c.survey_response ->> 'version', '') <> '2026-04-16@questions'
ORDER BY m.row_no;

-- 3g. Cross-check the match against the audit log, which records its own
--     occurred_at per submitted event (idx_audit_v2_crn_occurred).
--     Rows here disagree with the submitted_at-based match.
SELECT m.row_no, m.crn, m.contact_id, m.checkin_uuid, m.contact_at,
       a.occurred_at, abs(extract(epoch FROM (a.occurred_at - m.contact_at))) AS audit_delta_secs
FROM matched m
JOIN event_audit_log_v2 a ON a.checkin_uuid = m.checkin_uuid
 AND a.event_type = 'CHECKIN_SUBMITTED'
WHERE abs(extract(epoch FROM (a.occurred_at - m.contact_at))) > 3600
ORDER BY m.row_no;

-- ============================================================================
-- STEP 4: The work list — check-ins to fetch note text for
-- ============================================================================

\copy (select row_no, contact_id, crn, checkin_uuid, sensitive from matched where checkin_uuid is not null and has_answers order by row_no) to 'checkins_to_fetch.csv' with (format csv, header true)

-- ============================================================================
-- STEP 4b: JSONL export for the offline note generator
-- ============================================================================
-- Feeds src/main/kotlin/.../utils/CheckinNoteExportTool.kt, which reproduces the
-- note using the production formatter. Used instead of the API route when we
-- lack a token holding ROLE_ESUPERVISION__CHECK_IN__RO.
--
-- \o rather than \copy: \copy in csv format would quote and in text format would
-- backslash-escape, both of which corrupt embedded JSON. \o with unaligned,
-- tuples-only output writes each row_to_json result verbatim, one per line.
-- json_build_object escapes newlines inside strings, so every row stays on one line.

\pset format unaligned
\pset tuples_only on
\o checkin_export.jsonl

SELECT json_build_object(
         'row_no',          m.row_no,
         'contact_id',      m.contact_id,
         'crn',             m.crn,
         'checkinUuid',     c.uuid,
         'offenderUuid',    o.uuid,
         'submittedAt',     to_char(c.submitted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
         'autoIdCheck',     c.auto_id_check,
         'livenessEnabled', c.liveness_enabled,
         'livenessResult',  c.liveness_result,
         'sensitive',       c.sensitive,
         'surveyResponse',  c.survey_response
       )::text
FROM matched m
JOIN offender_checkin_v2 c ON c.uuid = m.checkin_uuid
JOIN offender_v2 o ON o.id = c.offender_id
WHERE m.checkin_uuid IS NOT NULL AND m.has_answers
ORDER BY m.row_no;

\o
\pset tuples_only off
\pset format aligned

-- ============================================================================
-- STEP 4c: JSONL export for whole-contact-note reconstruction
-- ============================================================================
-- Use this instead of STEP 4b if NDelius cannot amend just the submission part
-- of the contact note and has to replace the note wholesale. It carries the
-- same fields as 4b plus everything the REVIEWED branch of
-- EventDetailService.formatCheckinNotes needs:
--
--   manualIdCheck    -> "Is the person in the video the correct person: ..."
--   reviewAction     -> "What action are you taking after reviewing this check in: ..."
--
-- contactAt is NDelius's own date/time for the contact, parsed from the CSV
-- they supplied. The generator uses it to cross-check the timestamp it puts in
-- the "Comment added by" line, and can use it directly (--header-time=contact).
--
-- reviewedAt/reviewEntryAt date the review half. NDelius stamps each part of a
-- contact note with "Comment added by ... on DD/MM/YYYY at HH:MM", so the
-- generator needs a timestamp per part: submitted_at for the submission half,
-- reviewed_at (falling back to the log entry time) for the review half.
--
-- reviewAction reproduces the app's selection EXACTLY, including its quirk:
-- findAllCheckinEvents orders `by e.createdAt desc` (Repositories.kt:426) and
-- the service then takes .lastOrNull() (EventDetailService.kt:150), so the
-- entry that reaches the note is the OLDEST review-submitted entry, not the
-- newest -- the opposite of what the comment above that call claims. That has
-- been the ordering since before the affected window, so it is what NDelius
-- was sent. reviewEntryCount > 1 flags the only rows where it makes a
-- difference; check those by hand.
--
-- Check-ins that were never reviewed simply have status = 'SUBMITTED',
-- reviewedAt = null and no review fields; the generator emits the submission
-- part alone for those.
--
-- annotations carries any OFFENDER_CHECKIN_ANNOTATED entries. Those were
-- published as their own checkin-annotated events, so they are probably
-- separate NDelius notes rather than part of this one -- exported so you can
-- see whether any exist, NOT folded into the note by default.

\pset format unaligned
\pset tuples_only on
\o checkin_export_full.jsonl

SELECT json_build_object(
         'row_no',           m.row_no,
         'contact_id',       m.contact_id,
         'crn',              m.crn,
         'contactAt',        to_char(m.contact_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
         'checkinUuid',      c.uuid,
         'offenderUuid',     o.uuid,
         'submittedAt',      to_char(c.submitted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
         'autoIdCheck',      c.auto_id_check,
         'livenessEnabled',  c.liveness_enabled,
         'livenessResult',   c.liveness_result,
         'sensitive',        c.sensitive,
         'surveyResponse',   c.survey_response,
         'status',           c.status,
         'reviewedAt',       to_char(c.reviewed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
         'manualIdCheck',    c.manual_id_check,
         'reviewAction',     r.comment,
         'reviewEntryAt',    to_char(r.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
         'reviewEntryCount', coalesce(r.n, 0),
         'annotations',      coalesce(a.items, '[]'::json)
       )::text
FROM matched m
JOIN offender_checkin_v2 c ON c.uuid = m.checkin_uuid
JOIN offender_v2 o ON o.id = c.offender_id
LEFT JOIN LATERAL (
  -- count(*) OVER () is evaluated before LIMIT, so n is the full count
  SELECT e.comment, e.created_at, count(*) OVER () AS n
  FROM offender_event_log_v2 e
  WHERE e.checkin = c.id
    AND e.log_entry_type = 'OFFENDER_CHECKIN_REVIEW_SUBMITTED'
  ORDER BY e.created_at ASC
  LIMIT 1
) r ON true
LEFT JOIN LATERAL (
  SELECT json_agg(json_build_object(
           'createdAt',    to_char(e.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
           'practitioner', e.practitioner,
           'notes',        e.comment
         ) ORDER BY e.created_at) AS items
  FROM offender_event_log_v2 e
  WHERE e.checkin = c.id
    AND e.log_entry_type = 'OFFENDER_CHECKIN_ANNOTATED'
) a ON true
WHERE m.checkin_uuid IS NOT NULL AND m.has_answers
ORDER BY m.row_no;

\o
\pset tuples_only off
\pset format aligned

-- How many of the matched check-ins were ever reviewed, and how many carry the
-- ambiguities the generator will warn about.
SELECT c.status,
       count(*)                                                   AS checkins,
       count(*) FILTER (WHERE c.manual_id_check IS NULL)          AS no_manual_id_check,
       count(*) FILTER (WHERE c.reviewed_at IS NOT NULL)          AS reviewed
FROM matched m
JOIN offender_checkin_v2 c ON c.uuid = m.checkin_uuid
WHERE m.checkin_uuid IS NOT NULL AND m.has_answers
GROUP BY 1 ORDER BY 1;

-- Check-ins with more than one review-submitted entry: the app takes the
-- OLDEST (see above), so confirm that is the text NDelius holds.
SELECT m.row_no, m.crn, m.contact_id, count(*) AS review_entries,
       array_agg(e.created_at ORDER BY e.created_at) AS entry_times
FROM matched m
JOIN offender_checkin_v2 c ON c.uuid = m.checkin_uuid
JOIN offender_event_log_v2 e ON e.checkin = c.id
 AND e.log_entry_type = 'OFFENDER_CHECKIN_REVIEW_SUBMITTED'
GROUP BY 1, 2, 3 HAVING count(*) > 1
ORDER BY m.row_no;

-- ============================================================================
-- STEP 5: Verification oracle (does the ESUP-1956 resend give us real
--         formatter output to diff against?)
-- ============================================================================
-- Only relevant if we end up replicating formatSurvey in SQL rather than
-- reading notes from the API. These comments are genuine Kotlin formatter
-- output for real check-ins (CheckinNoteResendService.buildNotes).

SELECT count(*) AS system_annotations
FROM offender_event_log_v2
WHERE log_entry_type = 'OFFENDER_CHECKIN_ANNOTATED' AND practitioner = 'SYSTEM';

-- ============================================================================
-- Discard everything. See the note at the top of the file.
-- ============================================================================
ROLLBACK;
