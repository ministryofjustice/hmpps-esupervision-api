--liquibase formatted sql

--changeset Maddie-Williams:53_add_sensitive_flags
ALTER TABLE event_audit_log_v2 
    ADD COLUMN sensitive BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE offender_event_log_v2 
    ADD COLUMN sensitive BOOLEAN NOT NULL DEFAULT FALSE;

-- rollback ALTER TABLE event_audit_log_v2 DROP COLUMN sensitive;
-- rollback ALTER TABLE offender_event_log_v2 DROP COLUMN sensitive;