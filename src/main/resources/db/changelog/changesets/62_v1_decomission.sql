--liquibase formatted sql

--changeset roland.sadowski:62_v1_decomission splitStatements:false


DROP INDEX IF EXISTS "offender_setup_practitioner_idx";
DROP INDEX IF EXISTS "offender_setup_created_at_idx";
DROP INDEX IF EXISTS "offender_event_log_practitioner_idx";
DROP INDEX IF EXISTS "offender_event_log_offender_idx";
DROP INDEX IF EXISTS "offender_event_log_log_entry_type_idx";
DROP INDEX IF EXISTS "idx_chckin_notification_created_at";
DROP INDEX IF EXISTS "idx_checkin_notification_reference";
DROP INDEX IF EXISTS "offender_checkin_created_by_idx";
DROP INDEX IF EXISTS "offender_checkin_offender_idx";
DROP INDEX IF EXISTS "checkin_status_idx";
DROP INDEX IF EXISTS "checkin_created_at_idx";
DROP INDEX IF EXISTS "checkin_due_date_idx";
DROP INDEX IF EXISTS "offender_crn_idx";
DROP INDEX IF EXISTS "offender_practitioner";
DROP INDEX IF EXISTS "offender_created_at_idx";
DROP INDEX IF EXISTS "offender_status_idx";
DROP INDEX IF EXISTS "idx_job_log_created_at";
DROP INDEX IF EXISTS "idx_job_log_job_type";
DROP INDEX IF EXISTS "one_checkin_per_day";

DROP TABLE IF EXISTS "offender_setup";
DROP TABLE IF EXISTS "offender_event_log";
DROP TABLE IF EXISTS "checkin_notification";
DROP TABLE IF EXISTS "offender_checkin";
DROP TABLE IF EXISTS "checkin_status";
DROP TABLE IF EXISTS "offender";
DROP TABLE IF EXISTS "job_log";



