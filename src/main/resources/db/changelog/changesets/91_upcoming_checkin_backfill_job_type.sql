--liquibase formatted sql

-- changeset richard.birch:91_upcoming_checkin_backfill_job_type-1 runInTransaction:false
-- ESUP-2223: the one-off backfill that schedules the next check-in for everyone already set up.
ALTER TYPE job_type_v2 ADD VALUE IF NOT EXISTS 'V2_UPCOMING_CHECKIN_BACKFILL';

-- rollback:
-- No easy way to remove enum value in Postgres without dropping and recreating the type,
-- which is dangerous. Leaving it as is.
