--liquibase formatted sql

--changeset roland.sadowski:62_v1_decomission splitStatements:false

DROP TABLE IF EXISTS "offender_setup" cascade;
DROP TABLE IF EXISTS "offender_event_log" cascade;
DROP TABLE IF EXISTS "checkin_notification" cascade;
DROP TABLE IF EXISTS "offender_checkin" cascade;
DROP TABLE IF EXISTS "checkin_status" cascade;
DROP TABLE IF EXISTS generic_notification cascade;
DROP TABLE IF EXISTS "offender" cascade;
DROP TABLE IF EXISTS "job_log" cascade;



