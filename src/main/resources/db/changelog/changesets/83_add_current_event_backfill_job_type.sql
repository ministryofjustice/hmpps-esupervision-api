-- liquibase formatted sql

-- changeset hmpps:83_add_current_event_backfill_job_type runInTransaction:false
ALTER TYPE job_type_v2 ADD VALUE 'V2_CURRENT_EVENT_BACKFILL';

-- rollback:
-- No easy way to remove an enum value in PostgreSQL; leaving it in place.