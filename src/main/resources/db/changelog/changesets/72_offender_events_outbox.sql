-- liquibase formatted sql

-- changeset roland.sadowski:72_offender_events_outbox-1 splitStatements:false

CREATE OR REPLACE FUNCTION fn_add_outbox_record_on_offender_status_update()
    RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO outbox_items(type, entity_id)
    VALUES (
               CASE
                   WHEN NEW.STATUS = 'INACTIVE'::offender_status_v2 THEN 'OFFENDER_DEACTIVATED'::OutboxItemType
                   WHEN NEW.STATUS = 'VERIFIED'::offender_status_v2 THEN 'OFFENDER_SETUP_COMPLETE'::OutboxItemType
               END,
               NEW.id
           );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

--rollback drop function fn_add_outbox_record_on_offender_status_update();

-- changeset roland.sadowski:72_offender_events_outbox-2 splitStatements:false

CREATE TRIGGER trg_offender_status_update__outbox
    AFTER UPDATE on offender_v2
    FOR EACH ROW
    WHEN (NEW.status <> OLD.status and NEW.status in ('INACTIVE'::offender_status_v2, 'VERIFIED'::offender_status_v2))
EXECUTE FUNCTION fn_add_outbox_record_on_offender_status_update();

--rollback drop trigger trg_offender_status_update__outbox on offender_V2;
