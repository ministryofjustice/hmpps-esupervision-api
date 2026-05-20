--liquibase formatted sql

--changeset richard-birch:55_add_face_match_score_to_checkin_v2 splitStatements:false

ALTER TABLE offender_checkin_v2
    ADD COLUMN auto_id_check_score REAL;

--rollback ALTER TABLE offender_checkin_v2 DROP COLUMN auto_id_check_score;
