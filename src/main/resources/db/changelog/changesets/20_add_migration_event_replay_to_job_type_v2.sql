-- liquibase formatted sql

-- changeset roland:20_add_migration_event_replay_to_job_type_v2 splitStatements:false
ALTER TYPE job_type_v2 ADD VALUE 'MIGRATION_EVENT_REPLAY';
