--liquibase formatted sql

--changeset roland.sadowski:49_move_qs_assignment_trigger_to_insert splitStatements:false

DROP TRIGGER IF EXISTS trg_checkin_status_change ON offender_checkin_v2;

CREATE OR REPLACE FUNCTION fn_update_question_assignment()
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
EXECUTE FUNCTION fn_update_question_assignment();
