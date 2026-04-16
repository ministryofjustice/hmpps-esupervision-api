--liquibase formatted sql

--changeset richard-birch:45_add_liveness_columns_to_offender_checkin_v2 splitStatements:false

CREATE TYPE liveness_result AS ENUM (
    'LIVE',
    'NOT_LIVE',
    'ERROR'
);

ALTER TABLE offender_checkin_v2
    ADD COLUMN liveness_result liveness_result,
    ADD COLUMN liveness_confidence REAL;

ALTER TABLE event_audit_log_v2
    ADD COLUMN liveness_result liveness_result;

--rollback ALTER TABLE offender_checkin_v2 DROP COLUMN liveness_result, DROP COLUMN liveness_confidence;
--rollback ALTER TABLE event_audit_log_v2 DROP COLUMN liveness_result;
--rollback DROP TYPE liveness_result;