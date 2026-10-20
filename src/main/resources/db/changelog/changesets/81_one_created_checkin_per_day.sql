--liquibase formatted sql

--changeset hmpps:81_one_created_checkin_per_day-1 splitStatements:false

-- NOTE: we verified with the below query, that prod does not contain duplicates,
-- but DEV might contain them, so we will make sure we deal with that first
-- SELECT offender_id, due_date, COUNT(*)
-- FROM offender_checkin_v2
-- WHERE status = 'CREATED'
-- GROUP BY offender_id, due_date
-- HAVING COUNT(*) > 1;

WITH ranked AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY offender_id, due_date
            ORDER BY created_at DESC, id DESC
            ) AS position
    FROM offender_checkin_v2
    WHERE status = 'CREATED'
)
UPDATE offender_checkin_v2 AS c
SET status = 'CANCELLED'
FROM ranked AS r
WHERE c.id = r.id
  AND r.position > 1
  AND c.status = 'CREATED';

--changeset hmpps:81_one_created_checkin_per_day-2 splitStatements:false
CREATE UNIQUE INDEX uk_checkin_v2_created_offender_due_date
ON offender_checkin_v2 (offender_id, due_date)
WHERE status = 'CREATED';

--rollback DROP INDEX uk_checkin_v2_created_offender_due_date;
