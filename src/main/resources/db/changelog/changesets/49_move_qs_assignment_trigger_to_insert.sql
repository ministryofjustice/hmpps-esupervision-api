--liquibase formatted sql

--changeset roland.sadowski:49_move_qs_assignment_trigger_to_insert splitStatements:false

DROP TRIGGER IF EXISTS trg_checkin_status_change ON offender_checkin_v2;

ALTER FUNCTION fn_update_question_assignment() RENAME TO fn_update_question_assignment_on_update;

CREATE OR REPLACE FUNCTION fn_update_question_assignment_on_insert()
    RETURNS TRIGGER AS $$
BEGIN
    UPDATE question_list_assignment
    SET checkin_id = NEW.id,
        updated_at = now()
    WHERE offender_id = NEW.offender_id and checkin_id IS NULL;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_checkin_status_change
    AFTER INSERT ON offender_checkin_v2
    FOR EACH ROW
    WHEN (NEW.status = 'CREATED'::offender_checkin_status_v2)
EXECUTE FUNCTION fn_update_question_assignment_on_insert();

--rollback:
--rollback drop trigger if exists trg_checkin_status_change ON offender_checkin_v2;
--rollback drop function fn_update_question_assignment_on_insert;
--rollback ALTER FUNCTION fn_update_question_assignment_on_update() RENAME TO fn_update_question_assignment;
--rollback CREATE TRIGGER trg_checkin_status_change
--rollback     AFTER UPDATE ON offender_checkin_v2
--rollback     FOR EACH ROW
--rollback     WHEN (OLD.status = 'CREATED'::offender_checkin_status_v2 and OLD.STATUS IS DISTINCT FROM NEW.status)
--rollback  EXECUTE FUNCTION fn_update_question_assignment();
