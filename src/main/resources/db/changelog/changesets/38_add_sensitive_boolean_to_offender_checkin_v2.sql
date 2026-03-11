--liquibase formatted sql

--changeset Maddie-Williams:38_add_sensitive_boolean_to_offender_checkin_v2 splitStatements:false
ALTER TABLE offender_checkin_v2
    ADD COLUMN sensitive BOOLEAN DEFAULT FALSE;

--rollback ALTER TABLE offender_checkin_v2 DROP COLUMN sensitive;
