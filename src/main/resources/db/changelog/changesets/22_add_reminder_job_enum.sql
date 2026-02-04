--liquibase formatted sql

--changeset Maddie-Williams:22_add_reminder_job_enum runInTransaction:false
ALTER TYPE job_type_v2 ADD VALUE 'V2_CHECKIN_REMINDER';