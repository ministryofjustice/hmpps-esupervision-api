-- liquibase formatted sql

-- changeset roland:17_v2_use_enums-1 splitStatements:false
CREATE TYPE offender_status_v2 AS ENUM ('INITIAL', 'VERIFIED', 'INACTIVE');

ALTER TABLE offender_v2
ALTER COLUMN status TYPE offender_status_v2
USING status::text::offender_status_v2;

ALTER TABLE offender_v2 DROP CONSTRAINT offender_v2_status_check;

-- changeset roland:17_v2_use_enums-2 splitStatements:false
CREATE TYPE offender_checkin_status_v2 as ENUM ('CREATED', 'SUBMITTED', 'REVIEWED', 'EXPIRED', 'CANCELLED');
CREATE TYPE verify_id_manual_result_v2 as ENUM ('MATCH', 'NO_MATCH', 'NO_FACE_DETECTED', 'ERROR');
CREATE TYPE verify_id_auto_result_v2 as ENUM ('MATCH', 'NO_MATCH');

ALTER TABLE offender_checkin_v2
ALTER COLUMN status TYPE offender_checkin_status_v2
USING status::text::offender_checkin_status_v2;

ALTER TABLE offender_checkin_v2
ALTER COLUMN "auto_id_check" TYPE verify_id_auto_result_v2
USING "auto_id_check"::text::verify_id_auto_result_v2;

ALTER TABLE offender_checkin_v2
ALTER COLUMN "manual_id_check" TYPE verify_id_manual_result_v2
USING "manual_id_check"::text::verify_id_manual_result_v2;

ALTER TABLE offender_checkin_v2 DROP CONSTRAINT offender_checkin_v2_status_check;
ALTER TABLE offender_checkin_v2 DROP CONSTRAINT offender_checkin_v2_auto_id_check;
ALTER TABLE offender_checkin_v2 DROP CONSTRAINT offender_checkin_v2_manual_id_check;

-- changeset roland:17_v2_use_enums-3 splitStatements:false
CREATE TYPE generic_notification_recipient_v2 as ENUM ('OFFENDER', 'PRACTITIONER');
CREATE TYPE generic_notification_channel_v2 as ENUM ('SMS', 'EMAIL');

ALTER TABLE generic_notification_v2
ALTER COLUMN recipient_type TYPE generic_notification_recipient_v2
USING recipient_type::text::generic_notification_recipient_v2;

ALTER TABLE generic_notification_v2
ALTER COLUMN channel TYPE generic_notification_channel_v2
USING channel::text::generic_notification_channel_v2;

ALTER TABLE generic_notification_v2 DROP CONSTRAINT generic_notification_v2_recipient_type_check;
ALTER TABLE generic_notification_v2 DROP CONSTRAINT generic_notification_v2_channel_check;

-- changeset roland:17_v2_use_enums-4 splitStatements:false
CREATE TYPE job_type_v2 as ENUM ('V2_CHECKIN_CREATION', 'V2_CHECKIN_EXPIRY');

ALTER TABLE job_log_v2
ALTER COLUMN job_type TYPE job_type_v2
USING job_type::text::job_type_v2;

ALTER TABLE job_log_v2 DROP CONSTRAINT job_log_v2_job_type_check;
