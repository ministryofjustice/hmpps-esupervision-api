--liquibase formatted sql

--changeset richard.birch:100_upcoming_assignment_trusts_next_date-1 splitStatements:false

-- Same as changeset 88, except that p_next_checkin_date is now the expected check-in date
-- whenever the most recent check-in isn't open (ad hoc: unless it has passed). The caller (QuestionService.upcomingAssignment) works it out:
-- it skips dates no check-in will be created for, such as one the practitioner cancelled, and
-- moves past today once today's check-in has been dealt with. This function used to do the
-- latter itself by adding the interval, which could land on such a date.
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
                 SELECT id, checkin_mode
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
                WHEN the_offender.checkin_mode = 'AD_HOC'::checkin_mode AND p_next_checkin_date < p_today THEN NULL
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

-- rollback: re-apply the function body from 88_scheduled_checkin_activation-3
