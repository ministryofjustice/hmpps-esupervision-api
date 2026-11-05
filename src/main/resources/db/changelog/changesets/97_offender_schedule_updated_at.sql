--liquibase formatted sql

--changeset richard.birch:97_offender_schedule_updated_at-1 splitStatements:false

-- When a practitioner last set the offender's check-in schedule (setup, a schedule change, booking
-- an ad hoc check-in, or reactivation). A check-in a practitioner cancelled holds its date until
-- then: neither the creation job nor scheduling the next check-in reuses the date, but setting the
-- schedule again (rebooking) releases it. Other changes to the offender, such as their contact
-- preference or event, don't. NULL: not set since this column was added, so cancelled dates hold.
ALTER TABLE offender_v2 ADD COLUMN schedule_updated_at timestamp with time zone;

-- Existing offenders keep the behaviour they had, where any change released the date.
UPDATE offender_v2 SET schedule_updated_at = updated_at;

--rollback ALTER TABLE offender_v2 DROP COLUMN schedule_updated_at;
