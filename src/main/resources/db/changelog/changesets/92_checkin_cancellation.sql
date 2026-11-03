--liquibase formatted sql

--changeset richard.birch:92_checkin_cancellation-1 splitStatements:false runInTransaction:false

-- ESUP-2128: a practitioner can cancel a check-in. The reason is kept in the event log.
ALTER TYPE log_entry_type_v2 ADD VALUE IF NOT EXISTS 'OFFENDER_CHECKIN_CANCELLED';

--rollback SELECT 1;

--changeset richard.birch:92_checkin_cancellation-2 splitStatements:false

-- Set only when a practitioner cancels the check-in. A check-in cancelled by the system (check-ins
-- stopped, or a duplicate) leaves them null: unlike those, a check-in a practitioner cancelled is
-- not recreated by the creation job for its date (until the offender is next updated, for
-- example the practitioner books that date again).
ALTER TABLE offender_checkin_v2
    ADD COLUMN cancelled_at timestamp with time zone,
    ADD COLUMN cancelled_by varchar(255);

--rollback ALTER TABLE offender_checkin_v2 DROP COLUMN cancelled_at, DROP COLUMN cancelled_by;
