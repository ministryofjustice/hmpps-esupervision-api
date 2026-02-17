--liquibase formatted sql

--changeset roland:26_add_migration_checkin_events_table runInTransaction:false
create table migration_events_to_send (
    id BIGINT PRIMARY KEY,
    checkin UUID not null,
    sent_at TIMESTAMPTZ,
    notes TEXT
);

-- rollback drop table migration_events_to_send;