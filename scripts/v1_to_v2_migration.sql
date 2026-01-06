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
-- 2. Contact details will be fetched from Ndilius on-demand after migration
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
SELECT 'V1 Offenders' as table_name, COUNT(*) as record_count FROM offender WHERE status = 'VERIFIED';

-- Check V1 offenders with CRN (required for V2)
SELECT 'V1 Offenders with CRN' as table_name, COUNT(*) as record_count
FROM offender WHERE status = 'VERIFIED' AND crn IS NOT NULL AND crn != '';

-- Check V1 offenders without CRN (will be skipped)
SELECT 'V1 Offenders WITHOUT CRN (will be skipped)' as table_name, COUNT(*) as record_count
FROM offender WHERE status = 'VERIFIED' AND (crn IS NULL OR crn = '');

-- Check V1 checkin count
SELECT 'V1 Checkins' as table_name, COUNT(*) as record_count FROM offender_checkin;

-- Check V1 checkin by status
SELECT status, COUNT(*) as record_count FROM offender_checkin GROUP BY status ORDER BY status;

-- Check for CRN duplicates (V2 requires unique CRN)
SELECT crn, COUNT(*) as duplicate_count
FROM offender
WHERE status = 'VERIFIED' AND crn IS NOT NULL AND crn != ''
GROUP BY crn
HAVING COUNT(*) > 1;


-- ============================================================================
-- STEP 1: Migrate Offenders (V1 → V2)
-- ============================================================================
-- Only migrates VERIFIED offenders with valid CRN
-- PII fields (first_name, last_name, date_of_birth, email, phone_number) are NOT migrated
-- These will be fetched from Ndilius on-demand

BEGIN;

-- Create temporary mapping table to track V1 ID → V2 ID relationship
CREATE TEMP TABLE offender_id_mapping (
    v1_id BIGINT PRIMARY KEY,
    v2_id BIGINT NOT NULL,
    v1_uuid UUID NOT NULL,
    v2_uuid UUID NOT NULL,
    crn VARCHAR(7) NOT NULL
);

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
        o.uuid,  -- Preserve original UUID for S3 path compatibility
        UPPER(TRIM(o.crn)),
        o.practitioner,
        o.status::varchar(50),
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
    WHERE o.status = 'VERIFIED'
      AND o.crn IS NOT NULL
      AND TRIM(o.crn) != ''
      AND NOT EXISTS (
          SELECT 1 FROM offender_v2 v2 WHERE UPPER(TRIM(v2.crn)) = UPPER(TRIM(o.crn))
      )
    RETURNING id, uuid, crn
)
INSERT INTO offender_id_mapping (v1_id, v2_id, v1_uuid, v2_uuid, crn)
SELECT
    o.id as v1_id,
    io.id as v2_id,
    o.uuid as v1_uuid,
    io.uuid as v2_uuid,
    io.crn
FROM offender o
JOIN inserted_offenders io ON o.uuid = io.uuid;

-- Report migration results
SELECT 'Offenders migrated' as step, COUNT(*) as record_count FROM offender_id_mapping;

COMMIT;


-- ============================================================================
-- STEP 2: Migrate Checkins (V1 → V2)
-- ============================================================================
-- Only migrates checkins for offenders that were successfully migrated to V2

BEGIN;

-- Create temporary mapping for checkins
CREATE TEMP TABLE checkin_id_mapping (
    v1_id BIGINT PRIMARY KEY,
    v2_id BIGINT NOT NULL,
    v1_uuid UUID NOT NULL,
    v2_uuid UUID NOT NULL
);

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
        c.status::varchar(50),
        c.due_date,
        c.survey_response,
        c.created_at,
        c.created_by,
        c.submitted_at,
        c.review_started_at,
        NULL,  -- review_started_by not in V1
        c.reviewed_at,
        c.reviewed_by,
        c.checkin_started_at,
        c.id_check_auto::varchar(50),
        c.id_check_manual::varchar(50),
        NULL  -- risk_feedback not in V1
    FROM offender_checkin c
    JOIN offender_id_mapping m ON c.offender_id = m.v1_id
    WHERE NOT EXISTS (
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

COMMIT;


-- ============================================================================
-- STEP 3: Migrate Event Logs (V1 → V2)
-- ============================================================================
-- Optional: Only needed if historical event logs are required

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
    e.comment,
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

COMMIT;


-- ============================================================================
-- STEP 4: Post-Migration Validation
-- ============================================================================

-- Compare counts
SELECT 'V1 Offenders (VERIFIED with CRN)' as source, COUNT(*) as count
FROM offender WHERE status = 'VERIFIED' AND crn IS NOT NULL AND TRIM(crn) != ''
UNION ALL
SELECT 'V2 Offenders' as source, COUNT(*) as count FROM offender_v2
UNION ALL
SELECT 'V1 Checkins (for migrated offenders)' as source, COUNT(*) as count
FROM offender_checkin c
JOIN offender o ON c.offender_id = o.id
WHERE o.status = 'VERIFIED' AND o.crn IS NOT NULL AND TRIM(o.crn) != ''
UNION ALL
SELECT 'V2 Checkins' as source, COUNT(*) as count FROM offender_checkin_v2;

-- Check for any orphaned V1 checkins (checkins without migrated offender)
SELECT 'Orphaned V1 Checkins' as warning, COUNT(*) as count
FROM offender_checkin c
JOIN offender o ON c.offender_id = o.id
WHERE o.status != 'VERIFIED' OR o.crn IS NULL OR TRIM(o.crn) = '';

-- Verify UUID preservation (for S3 compatibility)
SELECT 'UUID mismatches (should be 0)' as check_name, COUNT(*) as count
FROM offender_id_mapping
WHERE v1_uuid != v2_uuid;


-- ============================================================================
-- ROLLBACK SCRIPTS (if needed)
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
WHERE uuid IN (SELECT uuid FROM offender_checkin);

-- Delete offenders
DELETE FROM offender_v2
WHERE uuid IN (SELECT uuid FROM offender WHERE status = 'VERIFIED');

COMMIT;
*/


-- ============================================================================
-- CLEANUP (Run after successful validation)
-- ============================================================================

-- Drop temporary mapping tables
DROP TABLE IF EXISTS offender_id_mapping;
DROP TABLE IF EXISTS checkin_id_mapping;
