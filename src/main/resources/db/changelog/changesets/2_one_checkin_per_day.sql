-- liquibase formatted sql

-- changeset roland.sadowski:2_one_checkin_per_day-1 splitStatements:false
CREATE UNIQUE INDEX "one_checkin_per_day"
ON offender_checkin (offender_id, due_date)
WHERE status not in ('CANCELLED');