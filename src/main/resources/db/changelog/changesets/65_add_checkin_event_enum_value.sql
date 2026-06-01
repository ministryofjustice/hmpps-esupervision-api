--liquibase formatted sql

--changeset roland.sadowski:65_add_checkin_event_enum_value-1 splitStatements:false

alter type OutboxItemType add value 'CHECKIN_ANNOTATED';

--rollback alter type OutboxItemType drop value 'CHECKIN_ANNOTATED';

--changeset roland.sadowski:65_add_checkin_event_enum_value-2 splitStatements:false

CREATE OR REPLACE FUNCTION fn_add_outbox_record_on_offender_event_log_insert()
    RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO outbox_items(type, entity_id)
    VALUES (
            'CHECKIN_ANNOTATED'::OutboxItemType,
            NEW.id
           );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_checkin_status_change__outbox
    AFTER INSERT on offender_event_log_v2
    FOR EACH ROW
    WHEN (NEW.log_entry_type in ('OFFENDER_CHECKIN_ANNOTATED'::log_entry_type_v2))
EXECUTE FUNCTION fn_add_outbox_record_on_offender_event_log_insert();

--rollback:
--rollback drop trigger trg_checkin_status_change__outbox on offender_event_log_v2;
--rollback drop function fn_add_outbox_record_on_offender_event_log_insert();
