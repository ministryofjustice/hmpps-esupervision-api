--liquibase formatted sql

--changeset roland.sadowski:37_offender_v2_add_event_id splitStatements:false
ALTER TABLE offender_v2
    -- refers to an event id in NDelius, any checkins should be linked to this event
    ADD COLUMN current_event bigint;

--rollback ALTER TABLE offender_v2 DROP COLUMN current_event;
