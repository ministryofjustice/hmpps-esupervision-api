--liquibase formatted sql

--changeset hmpps:76_add_checkin_mode_to_offender-1 splitStatements:false
CREATE TYPE checkin_mode AS ENUM ('SCHEDULED', 'AD_HOC');

ALTER TABLE offender_v2
  ADD COLUMN checkin_mode checkin_mode NOT NULL DEFAULT 'SCHEDULED';

ALTER TABLE offender_v2
  ALTER COLUMN checkin_interval DROP NOT NULL;

ALTER TABLE offender_v2
  ADD CONSTRAINT offender_v2_checkin_mode_check
  CHECK (
    (checkin_mode = 'SCHEDULED' AND checkin_interval IS NOT NULL) OR
    (checkin_mode = 'AD_HOC' AND checkin_interval IS NULL)
  );

--rollback:
--rollback ALTER TABLE offender_v2 DROP CONSTRAINT offender_v2_checkin_mode_check;
--rollback UPDATE offender_v2 SET checkin_interval = INTERVAL '1 month' where checkin_interval is null;
--rollback ALTER TABLE offender_v2 ALTER column checkin_interval SET NOT NULL;
--rollback ALTER TABLE offender_v2 DROP column checkin_mode;
--rollback DROP TYPE checkin_mode;

--changeset hmpps:76_add_checkin_mode_to_offender-2 splitStatements:false

create function get_upcoming_assignment_info_v2(p_offender_id bigint, p_today date, p_next_checkin_date date, p_checkin_window_days bigint)
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
                 WHERE (c.offender_id = p_offender_id
                     AND c.status = 'CREATED'::offender_checkin_status_v2)
                    OR (((p_next_checkin_date - c.due_date) < p_checkin_window_days)
                     AND MOD(c.due_date - the_offender.first_checkin,
                             (EXTRACT(EPOCH FROM the_offender.checkin_interval)) / 86400) = 0)
                 ORDER BY c.created_at DESC
                 LIMIT 1
             )
        SELECT
            COALESCE(i.question_list_id, d.question_list_id) AS question_list_id,
            CASE
                WHEN rc.status = 'CREATED'::offender_checkin_status_v2 THEN rc.due_date
                WHEN rc.status <> 'CREATED'::offender_checkin_status_v2 and the_offender.checkin_interval is not null THEN (p_next_checkin_date + the_offender.checkin_interval)::date
                WHEN the_offender.checkin_mode = 'AD_HOC'::checkin_mode and p_next_checkin_date < p_today THEN null
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

--rollback drop function get_upcoming_assignment_info_v2(p_offender_id bigint, p_today date, p_next_checkin_date date, p_checkin_window_days bigint);