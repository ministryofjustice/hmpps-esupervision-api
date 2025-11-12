--liquibase formatted sql

--changeset dave.iles:8_add_checkin_started_at_field-1
ALTER TABLE offender_checkin
    ADD COLUMN checkin_started_at TIMESTAMP(6) WITH TIME ZONE;
--rollback ALTER TABLE offender_checkin DROP COLUMN checkin_started_at;