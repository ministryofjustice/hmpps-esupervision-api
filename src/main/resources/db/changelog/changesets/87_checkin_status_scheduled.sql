--liquibase formatted sql

-- ESUP-2223: an upcoming check-in exists as a SCHEDULED row from the moment it is scheduled,
-- and becomes CREATED on its due date. The new value can only be used once this changeset
-- has committed, so the triggers and functions that use it live in changeset 88.

--changeset richard.birch:87_checkin_status_scheduled-1 splitStatements:false runInTransaction:false
ALTER TYPE offender_checkin_status_v2 ADD VALUE IF NOT EXISTS 'SCHEDULED' BEFORE 'CREATED';

-- rollback:
-- No easy way to remove enum value in Postgres without dropping and recreating the type,
-- which is dangerous. Leaving it as is.
