--liquibase formatted sql

--changeset richard.birch:85_scheduled_checkin_activation-1 splitStatements:false

-- Whether the check-in invite (the link) reached GOV.UK Notify. NULL for check-ins created
-- before ESUP-2223, which are not subject to the link-sent gate.
CREATE TYPE checkin_link_status_v2 AS ENUM ('PENDING', 'SENT', 'NOT_SENT');

ALTER TABLE offender_checkin_v2
    ADD COLUMN link_status checkin_link_status_v2,
    ADD COLUMN link_status_updated_at timestamp with time zone;

--rollback:
--rollback ALTER TABLE offender_checkin_v2 DROP COLUMN link_status_updated_at, DROP COLUMN link_status;
--rollback DROP TYPE checkin_link_status_v2;

--changeset richard.birch:85_scheduled_checkin_activation-2 splitStatements:false

-- A SCHEDULED check-in becomes CREATED on its due date. That activation must have the same
-- effects as inserting a CREATED row: a CHECKIN_CREATED outbox item and binding the pending
-- question list assignment. The INSERT triggers (changesets 49 and 67) stay for check-ins
-- created directly as CREATED.

CREATE TRIGGER trg_checkin_activation__outbox
    AFTER UPDATE ON offender_checkin_v2
    FOR EACH ROW
    WHEN (OLD.status = 'SCHEDULED'::offender_checkin_status_v2 AND NEW.status = 'CREATED'::offender_checkin_status_v2)
EXECUTE FUNCTION fn_add_outbox_record_on_checkin_status_update();

CREATE TRIGGER trg_checkin_activation__question_assignment
    AFTER UPDATE ON offender_checkin_v2
    FOR EACH ROW
    WHEN (OLD.status = 'SCHEDULED'::offender_checkin_status_v2 AND NEW.status = 'CREATED'::offender_checkin_status_v2)
EXECUTE FUNCTION fn_update_question_assignment_on_insert();

--rollback:
--rollback DROP TRIGGER IF EXISTS trg_checkin_activation__question_assignment ON offender_checkin_v2;
--rollback DROP TRIGGER IF EXISTS trg_checkin_activation__outbox ON offender_checkin_v2;

--changeset richard.birch:85_scheduled_checkin_activation-3 splitStatements:false

-- Same as changeset 74, except that SCHEDULED check-ins are ignored when looking for the most
-- recent check-in: a SCHEDULED row due today has not happened yet, so it must not be treated
-- as today's completed check-in.
CREATE OR REPLACE FUNCTION get_upcoming_assignment_info_v2(p_offender_id bigint, p_today date, p_next_checkin_date date, p_checkin_window_days bigint)
    returns TABLE(question_list_id bigint, due_date date, explicit_assignment boolean)
    stable
    language plpgsql
as
$$
BEGIN
    RETURN QUERY
        WITH info AS (
            SELECT qla.question_list_id, qla.offender_id, qla.checkin_id AS assigned_checkin,
                   c.id AS checkin, c.due_date
            FROM question_list_assignment qla
                     LEFT JOIN offender_checkin_v2 c ON qla.checkin_id = c.id
            WHERE qla.offender_id = p_offender_id
              AND (qla.checkin_id IS NULL OR c.status = 'CREATED')
            ORDER BY qla.created_at DESC
            LIMIT 1
        ),
             the_offender AS (
                 SELECT id, first_checkin, checkin_interval, checkin_mode
                 FROM offender_v2
                 WHERE id = p_offender_id
             ),
             default_question_list AS (
                 SELECT id AS question_list_id
                 FROM question_list
                 WHERE name = 'Default'
             ),
             recent_checkin AS (
                 SELECT c.due_date, c.status
                 FROM offender_checkin_v2 c
                 JOIN the_offender ON c.offender_id = the_offender.id
                 WHERE c.offender_id = p_offender_id
                   AND c.status <> 'SCHEDULED'::offender_checkin_status_v2
                   AND (c.status = 'CREATED'::offender_checkin_status_v2
                        OR (c.status <> 'CREATED'::offender_checkin_status_v2 AND c.due_date = p_today))
                 ORDER BY c.created_at DESC
                 LIMIT 1
             )
        SELECT
            COALESCE(i.question_list_id, d.question_list_id) AS question_list_id,
            CASE
                WHEN rc.status = 'CREATED'::offender_checkin_status_v2 THEN rc.due_date
                WHEN rc.status <> 'CREATED'::offender_checkin_status_v2 THEN
                    CASE
                        WHEN rc.due_date = p_today AND the_offender.checkin_mode = 'AD_HOC'::checkin_mode AND p_next_checkin_date > p_today THEN p_next_checkin_date
                        WHEN rc.due_date = p_today AND the_offender.checkin_mode = 'AD_HOC'::checkin_mode THEN NULL
                        WHEN rc.due_date = p_today AND the_offender.checkin_mode = 'SCHEDULED'::checkin_mode AND p_next_checkin_date > p_today THEN p_next_checkin_date
                        WHEN rc.due_date = p_today AND the_offender.checkin_mode = 'SCHEDULED'::checkin_mode THEN (p_next_checkin_date + the_offender.checkin_interval)::date
                    END
                ELSE
                    CASE
                        WHEN the_offender.checkin_mode = 'SCHEDULED'::checkin_mode THEN p_next_checkin_date
                        WHEN the_offender.checkin_mode = 'AD_HOC'::checkin_mode AND p_next_checkin_date >= p_today THEN p_next_checkin_date
                        ELSE NULL
                    END
            END AS due_date,
            (i.question_list_id IS NOT NULL) AS explicit_assignment
        FROM (SELECT 1) AS dummy
                 LEFT JOIN the_offender ON TRUE
                 LEFT JOIN recent_checkin rc ON TRUE
                 LEFT JOIN info i ON i.offender_id = p_offender_id
                 LEFT JOIN default_question_list d ON TRUE;
END;
$$;

-- rollback: re-apply the function body from 74_add_checkin_mode_to_offender-2
