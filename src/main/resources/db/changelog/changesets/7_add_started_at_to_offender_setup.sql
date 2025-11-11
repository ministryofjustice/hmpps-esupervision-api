--liquibase formatted sql

--changeset Maddie-Williams:add-started-at-to-offender-setup-1
ALTER TABLE offender_setup
ADD COLUMN started_at TIMESTAMP(6) WITH TIME ZONE;
--rollback ALTER TABLE offender_setup DROP COLUMN started_at;