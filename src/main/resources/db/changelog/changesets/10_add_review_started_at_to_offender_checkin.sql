--liquibase formatted sql

--changeset Maddie-Williams:add-review-started-at-to-offender-checkin-1
ALTER TABLE offender_checkin
ADD COLUMN review_started_at TIMESTAMP(6) WITH TIME ZONE;
--rollback ALTER TABLE offender_checkin DROP COLUMN review_started_at;