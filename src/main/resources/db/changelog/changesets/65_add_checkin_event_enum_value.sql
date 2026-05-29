--liquibase formatted sql

--changeset roland.sadowski:65_add_checkin_event_enum_value splitStatements:false

alter type OutboxItemType add value 'CHECKIN_ANNOTATED';

--rollback alter type OutboxItemType drop value 'CHECKIN_ANNOTATED';