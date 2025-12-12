--liquibase formatted sql

--changeset dave.iles:14_use_enum_for_log_entry_type-1 splitStatements:false
CREATE TYPE log_entry_type_v2 AS ENUM ('OFFENDER_SETUP_COMPLETE','OFFENDER_DEACTIVATED',
    'OFFENDER_CHECKIN_NOT_SUBMITTED','OFFENDER_CHECKIN_REVIEW_SUBMITTED','OFFENDER_CHECKIN_ANNOTATED',
    'OFFENDER_CHECKIN_RESCHEDULED','OFFENDER_CHECKIN_OUTSIDE_ACCESS');

ALTER TABLE offender_event_log_v2
ALTER COLUMN log_entry_type TYPE log_entry_type_v2
USING log_entry_type::text::log_entry_type_v2;

ALTER TABLE offender_event_log_v2 DROP CONSTRAINT offender_event_log_v2_log_entry_type_check;
