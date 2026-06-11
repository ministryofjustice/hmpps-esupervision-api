--liquibase formatted sql

--changeset roland.sadowski:67_checkin_creation_outbox.sql-1 splitStatements:false

CREATE OR REPLACE FUNCTION fn_add_outbox_record_on_checkin_status_update()
    RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO outbox_items(type, entity_id)
    VALUES (
               CASE
                   WHEN NEW.STATUS = 'CREATED'::offender_checkin_status_v2 THEN 'CHECKIN_CREATED'::OutboxItemType
                   WHEN NEW.status = 'SUBMITTED'::offender_checkin_status_v2 THEN 'CHECKIN_SUBMITTED'::OutboxItemType
                   WHEN NEW.status = 'REVIEWED'::offender_checkin_status_v2  THEN 'CHECKIN_REVIEWED'::OutboxItemType
                   --WHEN NEW.STATUS = 'EXPIRED'::offender_checkin_status_v2 THEN 'CHECKIN_EXPIRED'::OutboxItemType
                   END,
               NEW.id
           );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- we have to drop and recreate the trigger because now we want to handle inserts too
--DROP TRIGGER IF EXISTS trg_checkin_status_change__outbox ON offender_checkin_v2;

-- we create trigger on insert, but it will point to the same function as the one for update
CREATE TRIGGER trg_checkin_insert__outbox
    AFTER INSERT on offender_checkin_v2
    FOR EACH ROW
    WHEN (NEW.status in ('CREATED'::offender_checkin_status_v2))
EXECUTE FUNCTION fn_add_outbox_record_on_checkin_status_update();

--rollback:
--rollback DROP TRIGGER IF EXISTS trg_checkin_insert__outbox ON offender_checkin_v2;
