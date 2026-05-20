--liquibase formatted sql

--changeset richard-birch:56_add_rekognition_log_entry_types splitStatements:false runInTransaction:false

ALTER TYPE log_entry_type_v2 ADD VALUE IF NOT EXISTS 'OFFENDER_CHECKIN_LIVENESS_FAILED';
ALTER TYPE log_entry_type_v2 ADD VALUE IF NOT EXISTS 'OFFENDER_CHECKIN_FACE_MATCH_FAILED';

--rollback SELECT 'cannot remove enum values once added; manual intervention required to roll back';
