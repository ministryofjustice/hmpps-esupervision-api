--liquibase formatted sql

--changeset roland.sadowski:57_questions_upcoming_function.sql splitStatements:false

CREATE OR REPLACE FUNCTION get_upcoming_assignment_info(
    p_offender_id          bigint,
    p_next_checkin_date    date,
    p_checkin_window_days  bigint
)
RETURNS TABLE(
                 question_list_id     bigint,
                 due_date             date,
                 explicit_assignment  boolean
             )
LANGUAGE plpgsql STABLE
AS $$
BEGIN
    RETURN QUERY
        WITH info AS (
            SELECT qla.question_list_id, qla.offender_id, qla.checkin_id AS assigned_checkin,
                   c.id AS checkin, c.due_date
            FROM question_list_assignment qla
                     JOIN offender_v2 o ON qla.offender_id = o.id
                     LEFT JOIN offender_checkin_v2 c ON qla.checkin_id = c.id
            WHERE qla.offender_id = p_offender_id
              AND (qla.checkin_id IS NULL OR c.status = 'CREATED')
            ORDER BY qla.created_at DESC
            LIMIT 1
        ),
        the_offender AS (
            SELECT id, first_checkin, checkin_interval
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
            WHERE (c.offender_id = p_offender_id
              AND c.status = 'CREATED'::offender_checkin_status_v2)
               OR (((p_next_checkin_date - c.due_date) < p_checkin_window_days)
                   AND MOD(c.due_date - the_offender.first_checkin,
                           EXTRACT(DAY FROM the_offender.checkin_interval)) = 0)
            ORDER BY c.created_at DESC
            LIMIT 1
        )
        SELECT
            COALESCE(i.question_list_id, d.question_list_id) AS question_list_id,
            CASE
                WHEN rc.status = 'CREATED'::offender_checkin_status_v2 THEN rc.due_date
                WHEN rc.status <> 'CREATED'::offender_checkin_status_v2 THEN (p_next_checkin_date + the_offender.checkin_interval)::date
                ELSE p_next_checkin_date
            END AS due_date,
            (i.question_list_id IS NOT NULL) AS explicit_assignment
        FROM (SELECT 1) AS dummy
                 LEFT JOIN the_offender ON TRUE
                 LEFT JOIN recent_checkin rc ON TRUE
                 LEFT JOIN info i ON i.offender_id = p_offender_id
                 LEFT JOIN default_question_list d ON TRUE;
END;
$$;

--rollback drop function get_upcoming_assignment_info;