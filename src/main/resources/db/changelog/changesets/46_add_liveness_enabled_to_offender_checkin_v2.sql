--liquibase formatted sql

--changeset richard-birch:46_add_liveness_enabled_to_offender_checkin_v2

ALTER TABLE offender_checkin_v2
    ADD COLUMN liveness_enabled BOOLEAN NOT NULL DEFAULT FALSE;

--rollback ALTER TABLE offender_checkin_v2 DROP COLUMN liveness_enabled;
