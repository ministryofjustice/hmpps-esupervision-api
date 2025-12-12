--liquibase formatted sql

--changeset dave.iles:16_add_risk_feedback_to_checkins-1 splitStatements:false
ALTER TABLE offender_checkin_v2
    ADD COLUMN risk_feedback BOOLEAN;
--rollback ALTER TABLE offender_checkin_v2 DROP COLUMN risk_feedback;