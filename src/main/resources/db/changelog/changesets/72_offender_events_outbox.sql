-- liquibase formatted sql

-- changeset roland.sadowski:72_offender_events_outbox-1 splitStatements:false runInTransaction:false

ALTER TYPE OutboxItemType ADD VALUE 'OFFENDER_REACTIVATED' AFTER 'OFFENDER_DEACTIVATED';
