-- liquibase formatted sql

-- changeset roland.sadowski:4_new_event_type_checkin_reschedule-1 splitStatements:false
alter table offender_event_log
drop constraint offender_event_log_log_entry_type_check;

-- changeset roland.sadowski:4_new_event_type_checkin_reschedule-2 splitStatements:false
alter table offender_event_log add
    constraint offender_event_log_log_entry_type_check
        check ((log_entry_type)::text = ANY((ARRAY [
            'OFFENDER_SETUP_COMPLETE'::character varying,
            'OFFENDER_DEACTIVATED'::character varying,
            'OFFENDER_CHECKIN_NOT_SUBMITTED'::character varying,
            'OFFENDER_CHECKIN_RESCHEDULED'::character varying])::text[])
            );