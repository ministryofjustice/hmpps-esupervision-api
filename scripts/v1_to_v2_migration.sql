-- ============================================================================
-- V1 to V2 Data Migration Script - PHASE 1 (DATA ONLY)
-- ============================================================================
-- This script copies data from V1 tables to V2 tables.
--
-- IMPORTANT: This is Phase 1 only (data migration).
-- Phase 2 (domain event replay) must be run via the API to publish events
-- to Ndilius. See docs/V1_TO_V2_MIGRATION.md for the complete process.
--
-- NOTES:
-- 1. V2 does NOT store PII - only CRN is migrated from offender table
-- 2. Contact details will be fetched from NDelius on-demand after migration
-- 3. UUIDs are preserved for S3 media file compatibility
-- 4. Run in a transaction and test on non-production first
--
-- PREREQUISITES:
-- - All V2 tables must exist (run liquibase migrations first)
-- - Backup production database before running
-- ============================================================================

-- ============================================================================
-- STEP 0: Pre-Migration Validation Queries (Run these first)
-- ============================================================================

-- Check V1 offender count
SELECT 'V1 Offenders' as table_name, COUNT(*) as record_count FROM offender
WHERE status in ('VERIFIED', 'INACTIVE') and (crn !~* '^X' and crn != 'A123456');

-- Check V1 offenders with CRN (required for V2)
SELECT 'V1 Offenders with CRN' as table_name, COUNT(*) as record_count
FROM offender WHERE status in ('VERIFIED', 'INACTIVE') AND crn IS NOT NULL AND crn != '' and (crn !~* '^X' and crn != 'A123456');

-- Check V1 offenders without CRN (will be skipped)
SELECT 'V1 Offenders WITHOUT CRN (will be skipped)' as table_name, COUNT(*) as record_count
FROM offender WHERE status in ('VERIFIED', 'INACTIVE') AND (crn IS NULL OR crn = '');

-- Check V1 checkin count
SELECT 'V1 Checkins' as table_name, COUNT(*) as record_count
FROM offender_checkin c JOIN offender o ON c.offender_id = o.id
WHERE o.crn is not null AND (crn !~* '^X' and crn != 'A123456');

-- Check V1 checkin by status
SELECT c.status, COUNT(*) as record_count
FROM offender_checkin c  JOIN offender o ON c.offender_id = o.id
WHERE o.crn is not null AND (crn !~* '^X' and crn != 'A123456')
GROUP BY c.status ORDER BY c.status;

-- Check for CRN duplicates (V2 requires unique CRN)
SELECT crn, status, COUNT(*) as duplicate_count
FROM offender
WHERE status in ('VERIFIED', 'INACTIVE') AND crn IS NOT NULL AND crn != '' AND (crn !~* '^X' and crn != 'A123456')
GROUP BY crn, status
HAVING COUNT(*) > 1;


-- ============================================================================
-- STEP 1: Migrate Offenders (V1 → V2)
-- ============================================================================
-- Only migrates VERIFIED offenders with valid CRN
-- PII fields (first_name, last_name, date_of_birth, email, phone_number) are NOT migrated
-- These will be fetched from Ndilius on-demand

-- Create temporary mapping table to track V1 ID → V2 ID relationship
CREATE TEMP TABLE offender_id_mapping (
                                          v1_id BIGINT PRIMARY KEY,
                                          v2_id BIGINT NOT NULL,
                                          v1_uuid UUID NOT NULL,
                                          crn VARCHAR(7) NOT NULL
);

CREATE TEMP TABLE offender_crn_mapping (
                                           crn VARCHAR(7) PRIMARY KEY UNIQUE NOT NULL,
                                           v1_id BIGINT NOT NULL);


CREATE TEMP TABLE checkin_id_mapping (
                                         v1_id BIGINT PRIMARY KEY,
                                         v2_id BIGINT NOT NULL,
                                         v1_uuid UUID NOT NULL,
                                         v2_uuid UUID NOT NULL
);

CREATE TEMP TABLE migration_counts (
                                       label VARCHAR(255),
                                       record_count BIGINT
);

BEGIN;

insert into migration_counts(label, record_count)
values ('offenders_v2_before', (select count(*) offenders_v2_to_migrate from offender_v2));

-- Insert one row per CRN (keep the last by offender.created_at)
WITH ranked_offenders AS (
    SELECT
        id,
        uuid,
        crn,
        ROW_NUMBER() OVER (
            PARTITION BY UPPER(TRIM(crn))
            ORDER BY created_at DESC, id DESC
            ) AS rn
    FROM offender
    WHERE
        status IN ('VERIFIED', 'INACTIVE')
      AND crn IS NOT NULL AND TRIM(crn) != ''
      AND (crn !~* '^X' AND crn != 'A123456')
)
INSERT INTO offender_crn_mapping (v1_id, crn)
SELECT
    id AS v1_id,
    UPPER(TRIM(crn)) AS crn
FROM ranked_offenders
WHERE rn = 1;

-- Insert offenders into V2 (generating new IDs)
-- Only include VERIFIED offenders with valid CRN
WITH inserted_offenders AS (
    INSERT INTO offender_v2 (
                             uuid,
                             crn,
                             practitioner_id,
                             status,
                             first_checkin,
                             checkin_interval,
                             created_at,
                             created_by,
                             updated_at,
                             contact_preference
        )
        SELECT
            o.uuid,  -- Preserve the original UUID for S3 path compatibility
            UPPER(TRIM(o.crn)),
            o.practitioner,
            offender_status_v2(o.status),
            COALESCE(o.first_checkin, CURRENT_DATE),  -- Default if null
            o.checkin_interval,
            o.created_at,
            o.practitioner,  -- created_by = practitioner
            o.updated_at,
            CASE
                WHEN o.phone_number IS NOT NULL THEN 'PHONE'::contact_type_v2
                WHEN o.email IS NOT NULL THEN 'EMAIL'::contact_type_v2
                ELSE 'PHONE'::contact_type_v2  -- Default
                END
        FROM offender o
                 JOIN offender_crn_mapping m ON m.v1_id = o.id
        RETURNING id, uuid, crn
)
INSERT INTO offender_id_mapping (v1_id, v2_id, v1_uuid, crn)
SELECT
    o.id as v1_id,
    io.id as v2_id,
    o.uuid as v1_uuid,
    io.crn
FROM offender o
         JOIN inserted_offenders io ON o.uuid = io.uuid;

insert into migration_counts(label, record_count)
values ('offenders_v2_after', (select count(*) offenders_v2_to_migrate from offender_v2));

-- Report migration results
SELECT 'Unique CRNs migrated' as step, COUNT(*) as record_count FROM offender_crn_mapping;
SELECT 'Offenders migrated' as step, COUNT(*) as record_count FROM offender_id_mapping;
SELECT label, record_count FROM migration_counts;
COMMIT;

-- ============================================================================
-- STEP 2: Migrate Checkins (V1 → V2)
-- ============================================================================
-- Only migrates checkins for offenders that were successfully migrated to V2

BEGIN;

insert into migration_counts(label, record_count)
values ('checkins_v2_before', (select count(*) checkins_v2_to_migrate from offender_checkin_v2));

-- Insert checkins into V2
WITH inserted_checkins AS (
    INSERT INTO offender_checkin_v2 (
                                     uuid,
                                     offender_id,
                                     status,
                                     due_date,
                                     survey_response,
                                     created_at,
                                     created_by,
                                     submitted_at,
                                     review_started_at,
                                     review_started_by,
                                     reviewed_at,
                                     reviewed_by,
                                     checkin_started_at,
                                     auto_id_check,
                                     manual_id_check,
                                     risk_feedback
        )
        SELECT
            c.uuid,  -- Preserve original UUID for S3 path compatibility
            m.v2_id,  -- Link to V2 offender
            offender_checkin_status_v2(c.status::text),
            c.due_date,
            c.survey_response,
            c.created_at,
            c.created_by,
            c.submitted_at,
            c.review_started_at,
            c.reviewed_by,  -- review_started_by not in V1, default to reviewed_by
            c.reviewed_at,
            c.reviewed_by,
            c.checkin_started_at,
            verify_id_auto_result_v2(c.id_check_auto::text),
            verify_id_manual_result_v2(c.id_check_manual::text),
            NULL  -- risk_feedback not in V1
        from offender_crn_mapping cm
                 join offender o on o.crn = cm.crn
                 join offender_id_mapping m on m.crn = cm.crn
                 join offender_checkin c on c.offender_id = o.id
        where o.status not in ('INITIAL')
          and NOT EXISTS (
            SELECT 1 FROM offender_checkin_v2 v2 WHERE v2.uuid = c.uuid
        )
        RETURNING id, uuid
)
INSERT INTO checkin_id_mapping (v1_id, v2_id, v1_uuid, v2_uuid)
SELECT
    c.id as v1_id,
    ic.id as v2_id,
    c.uuid as v1_uuid,
    ic.uuid as v2_uuid
FROM offender_checkin c
         JOIN inserted_checkins ic ON c.uuid = ic.uuid;

-- Report migration results
SELECT 'Checkins migrated' as step, COUNT(*) as record_count FROM checkin_id_mapping;

-- Breakdown by status
SELECT 'Checkins by status' as step, c.status, COUNT(*) as record_count
FROM offender_checkin c
         JOIN checkin_id_mapping m ON c.id = m.v1_id
GROUP BY c.status
ORDER BY c.status;

insert into migration_counts(label, record_count)
values ('checkins_v2_after', (select count(*) from offender_checkin_v2));

select * from migration_counts;

COMMIT;


-- ============================================================================
-- STEP 3: Migrate Event Logs (V1 → V2)
-- ============================================================================

BEGIN;

INSERT INTO offender_event_log_v2 (
    comment,
    created_at,
    log_entry_type,
    practitioner,
    uuid,
    checkin,
    offender_id
)
SELECT
    concat(e.comment, '\n', 'Created by migration from V1'),
    e.created_at,
    e.log_entry_type::log_entry_type_v2,
    e.practitioner,
    e.uuid,
    cm.v2_id,  -- Link to V2 checkin
    om.v2_id   -- Link to V2 offender
FROM offender_event_log e
         LEFT JOIN checkin_id_mapping cm ON e.checkin = cm.v1_id
         LEFT JOIN offender_id_mapping om ON e.offender_id = om.v1_id
WHERE NOT EXISTS (
    SELECT 1 FROM offender_event_log_v2 v2 WHERE v2.uuid = e.uuid
);

SELECT 'Event logs migrated' as step, COUNT(*) as record_count
FROM offender_event_log_v2;

insert into migration_counts (label, record_count)
values
    ('event_logs_v2', (select count(*) from offender_event_log_v2)),
    ('event_logs_v2_migrated', (select count(*)
                                from offender_event_log_v2
                                where offender_id in (select v2_id from offender_id_mapping)));

select * from migration_counts;

COMMIT;

-- see the events
select id from offender_event_log_v2 e
where e.offender_id in (select v2_id from offender_id_mapping);

-- see the checkins and if they match the events
select id,status,due_date from offender_checkin_v2 where offender_id in (select v2_id from offender_id_mapping);

-- ============================================================================
-- STEP 4: Create audit log
-- ============================================================================

BEGIN;

insert into migration_counts (label, record_count)
values ('audit_log_before/SETUP_COMPLETED', (select count(*) from event_audit_log_v2));

-- offender setup completed events
INSERT INTO event_audit_log_v2 (
    event_type,
    occurred_at,
    crn,
    practitioner_id,
    local_admin_unit_code,
    local_admin_unit_description,
    pdu_code,
    pdu_description,
    provider_code,
    provider_description,
    checkin_uuid,
    checkin_status,
    checkin_due_date,
    time_to_submit_hours,
    time_to_review_hours,
    review_duration_hours,
    auto_id_check_result,
    manual_id_check_result,
    notes)
SELECT
    'SETUP_COMPLETED',
    o.created_at,
    o.crn,
    o.practitioner_id,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    'Created by migration from V1'
FROM offender_v2 o
         join offender_crn_mapping cm on o.crn = cm.crn
         join offender_id_mapping om on om.v2_id = o.id;

insert into migration_counts (label, record_count)
values ('audit_log_after/SETUP_COMPLETED', (select count(*) from event_audit_log_v2));

-- offender deactivation events
INSERT INTO event_audit_log_v2 (
    event_type,
    occurred_at,
    crn,
    practitioner_id,
    local_admin_unit_code,
    local_admin_unit_description,
    pdu_code,
    pdu_description,
    provider_code,
    provider_description,
    checkin_uuid,
    checkin_status,
    checkin_due_date,
    time_to_submit_hours,
    time_to_review_hours,
    review_duration_hours,
    auto_id_check_result,
    manual_id_check_result,
    notes)
SELECT
    'OFFENDER_DEACTIVATED',
    e.created_at,
    o.crn,
    o.practitioner_id,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    'Created by migration from V1'
FROM offender_v2 o
         join offender_crn_mapping cm on o.crn = cm.crn
         join offender_id_mapping om on om.v2_id = o.id
         join offender_event_log_v2 e on e.offender_id = o.id and e.log_entry_type = 'OFFENDER_DEACTIVATED';

insert into migration_counts (label, record_count)
values ('audit_log_after/OFFENDER_DEACTIVATED', (select count(*) from event_audit_log_v2));

COMMIT;


BEGIN;

insert into migration_counts (label, record_count)
values ('audit_log_before/CHECKIN_CREATED', (select count(*) from event_audit_log_v2));

-- checkin created events
INSERT INTO event_audit_log_v2 (
    event_type,
    occurred_at,
    crn,
    practitioner_id,
    local_admin_unit_code,
    local_admin_unit_description,
    pdu_code,
    pdu_description,
    provider_code,
    provider_description,
    checkin_uuid,
    checkin_status,
    checkin_due_date,
    time_to_submit_hours,
    time_to_review_hours,
    review_duration_hours,
    auto_id_check_result,
    manual_id_check_result,
    notes)
SELECT'CHECKIN_CREATED',
      c.created_at,
      o.crn,
      o.practitioner_id,
      null,
      null,
      null,
      null,
      null,
      null,
      c.uuid,
      'CREATED',
      c.due_date,
      null,
      null,
      null,
      null,
      null,
      'Created by migration from V1'
FROM OFFENDER_CHECKIN_V2 c
         JOIN CHECKIN_ID_MAPPING ON c.id = CHECKIN_ID_MAPPING.v2_id
         JOIN offender_v2 o ON c.offender_id = o.id;

insert into migration_counts (label, record_count)
values ('audit_log_after/CHECKIN_CREATED', (select count(*) from event_audit_log_v2));

-- checkins submitted events
INSERT INTO event_audit_log_v2 (
    event_type,
    occurred_at,
    crn,
    practitioner_id,
    local_admin_unit_code,
    local_admin_unit_description,
    pdu_code,
    pdu_description,
    provider_code,
    provider_description,
    checkin_uuid,
    checkin_status,
    checkin_due_date,
    time_to_submit_hours,
    time_to_review_hours,
    review_duration_hours,
    auto_id_check_result,
    manual_id_check_result,
    notes)
SELECT'CHECKIN_SUBMITTED',
      c.submitted_at,
      o.crn,
      o.practitioner_id,
      null,
      null,
      null,
      null,
      null,
      null,
      c.uuid,
      'SUBMITTED',
      c.due_date,
      EXTRACT(EPOCH FROM (c.submitted_at - c.checkin_started_at)) / 3600,
      null,
      null,
      c.auto_id_check,
      null,
      'Created by migration from V1'
FROM OFFENDER_CHECKIN_V2 c
         JOIN CHECKIN_ID_MAPPING ON c.id = CHECKIN_ID_MAPPING.v2_id
         JOIN offender_v2 o ON c.offender_id = o.id
where c.submitted_at is not null;

insert into migration_counts (label, record_count)
values ('audit_log_after/CHECKIN_SUBMITTED', (select count(*) from event_audit_log_v2));

-- checkins reviewed events
INSERT INTO event_audit_log_v2 (
    event_type,
    occurred_at,
    crn,
    practitioner_id,
    local_admin_unit_code,
    local_admin_unit_description,
    pdu_code,
    pdu_description,
    provider_code,
    provider_description,
    checkin_uuid,
    checkin_status,
    checkin_due_date,
    time_to_submit_hours,
    time_to_review_hours,
    review_duration_hours,
    auto_id_check_result,
    manual_id_check_result,
    notes)
SELECT'CHECKIN_REVIEWED',
      c.created_at,
      o.crn,
      o.practitioner_id,
      null,
      null,
      null,
      null,
      null,
      null,
      c.uuid,
      'REVIEWED',
      c.due_date,
      null,
      EXTRACT(EPOCH FROM (c.review_started_at - c.submitted_at)) / 3600,
      EXTRACT(EPOCH FROM (c.reviewed_at - c.review_started_by)) / 3600,
      c.auto_id_check,
      c.manual_id_check,
      'Created by migration from V1'
FROM OFFENDER_CHECKIN_V2 c
         JOIN CHECKIN_ID_MAPPING ON c.id = CHECKIN_ID_MAPPING.v2_id
         JOIN offender_v2 o ON c.offender_id = o.id
where c.reviewed_at is not null;

insert into migration_counts (label, record_count)
values ('audit_log_after/CHECKIN_REVIEWED', (select count(*) from event_audit_log_v2));

-- checkin expired events
INSERT INTO event_audit_log_v2 (
    event_type,
    occurred_at,
    crn,
    practitioner_id,
    local_admin_unit_code,
    local_admin_unit_description,
    pdu_code,
    pdu_description,
    provider_code,
    provider_description,
    checkin_uuid,
    checkin_status,
    checkin_due_date,
    time_to_submit_hours,
    time_to_review_hours,
    review_duration_hours,
    auto_id_check_result,
    manual_id_check_result,
    notes)
SELECT'CHECKIN_EXPIRED',
      (c.due_date::timestamptz) + INTERVAL '3 days',
      o.crn,
      o.practitioner_id,
      null,
      null,
      null,
      null,
      null,
      null,
      c.uuid,
      'EXPIRED',
      c.due_date,
      null,
      null,
      null,
      null,
      null,
      'Created by migration from V1'
FROM OFFENDER_CHECKIN_V2 c
         JOIN CHECKIN_ID_MAPPING ON c.id = CHECKIN_ID_MAPPING.v2_id
         JOIN offender_v2 o ON c.offender_id = o.id
where c.status = 'EXPIRED';

insert into migration_counts (label, record_count)
values ('audit_log_after/CHECKIN_EXPIRED', (select count(*) from event_audit_log_v2));

COMMIT;

-- ============================================================================
-- STEP 4: Post-Migration Validation
-- ============================================================================

-- Compare counts
SELECT 'V1 Offenders (VERIFIED with CRN)' as source, COUNT(*) as count
FROM offender WHERE status in ('VERIFIED', 'INACTIVE') AND crn IS NOT NULL AND TRIM(crn) != ''
UNION ALL
SELECT 'V2 Offenders' as source, COUNT(*) as count FROM offender_v2
UNION ALL
SELECT 'V1 Checkins (for migrated offenders)' as source, COUNT(*) as count
FROM offender_checkin c
         JOIN offender o ON c.offender_id = o.id
WHERE o.status in ('VERIFIED', 'INACTIVE') AND o.crn IS NOT NULL AND TRIM(o.crn) != ''
UNION ALL
SELECT 'V2 Checkins' as source, COUNT(*) as count FROM offender_checkin_v2;

-- Check for any orphaned V1 checkins (checkins without migrated offender)
SELECT 'Orphaned V1 Checkins' as warning, COUNT(*) as count
FROM offender_checkin c
         JOIN offender o ON c.offender_id = o.id
WHERE o.status not in ('VERIFIED', 'INACTIVE') OR o.crn IS NULL OR TRIM(o.crn) = '';

-- ============================================================================
-- ROLLBACK SCRIPTS
-- ============================================================================
-- WARNING: Only run these if migration needs to be reversed
-- These will DELETE all migrated data from V2 tables

/*
-- Rollback: Delete migrated data from V2 tables
BEGIN;

-- Delete event logs first (foreign key dependency)
DELETE FROM offender_event_log_v2
WHERE uuid IN (SELECT uuid FROM offender_event_log);

-- Delete checkins (foreign key dependency)
DELETE FROM offender_checkin_v2
WHERE uuid IN (SELECT v2_uuid FROM checkin_id_mapping);

-- Delete offenders
DELETE FROM offender_v2
WHERE uuid IN (SELECT v1_uuid from offender_id_mapping);

DELETE FROM event_audit_log_v2 e
WHERE e.notes like '%Created by migration from V1%';

COMMIT;
*/


-- ============================================================================
-- CLEANUP (Run after successful validation)
-- ============================================================================

-- Drop temporary mapping tables
DROP TABLE IF EXISTS offender_id_mapping;
DROP TABLE IF EXISTS checkin_id_mapping;
DROP TABLE IF EXISTS migration_counts;
