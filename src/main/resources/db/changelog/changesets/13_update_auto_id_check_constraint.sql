--liquibase formatted sql

--changeset esupervision:13_update_auto_id_check_constraint

-- Add NO_FACE_DETECTED and ERROR to the auto_id_check constraint
-- These new values are needed for the V2 Rekognition service which distinguishes
-- between different failure modes (no face detected vs faces don't match vs AWS error)

ALTER TABLE "offender_checkin_v2"
    DROP CONSTRAINT "offender_checkin_v2_auto_id_check";

ALTER TABLE "offender_checkin_v2"
    ADD CONSTRAINT "offender_checkin_v2_auto_id_check"
    CHECK (auto_id_check IN ('MATCH', 'NO_MATCH', 'NO_FACE_DETECTED', 'ERROR'));
