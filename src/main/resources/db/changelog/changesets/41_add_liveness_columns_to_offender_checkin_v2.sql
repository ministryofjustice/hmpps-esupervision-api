--liquibase formatted sql

--changeset richard-birch:41_add_liveness_columns_to_offender_checkin_v2 splitStatements:false
ALTER TABLE offender_checkin_v2
    ADD COLUMN liveness_result VARCHAR(10),
    ADD COLUMN liveness_confidence REAL;

ALTER TABLE event_audit_log_v2
    ADD COLUMN liveness_result VARCHAR(10);

--rollback ALTER TABLE offender_checkin_v2 DROP COLUMN liveness_result, DROP COLUMN liveness_confidence;
--rollback ALTER TABLE event_audit_log_v2 DROP COLUMN liveness_result;
