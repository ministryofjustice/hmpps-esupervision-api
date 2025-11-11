-- liquibase formatted sql

-- changeset roland.sadowski:8_change_offender_event_log_comment_to_text-1 splitStatements:false
ALTER TABLE offender_event_log
ALTER COLUMN comment TYPE TEXT;

-- rollback ALTER TABLE offender_event_log ALTER COLUMN comment TYPE VARCHAR(255);