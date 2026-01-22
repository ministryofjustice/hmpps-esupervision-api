--liquibase formatted sql

--changeset rob.catton:20_create_stats_summary_v1 splitStatements:false

CREATE MATERIALIZED VIEW stats_summary_v1 AS
WITH offenders AS (
    SELECT
        COUNT(*) AS total_signed_up,
        COUNT(*) FILTER (WHERE status = 'VERIFIED') AS active_users,
        COUNT(*) FILTER (WHERE status = 'INACTIVE') AS inactive_users
    FROM offender_v2
),

checkins AS (
    SELECT
        COUNT(*) FILTER (
            WHERE status IN ('SUBMITTED', 'REVIEWED')
        ) AS completed_checkins,

        COUNT(*) FILTER (
            WHERE status = 'EXPIRED'
               OR (submitted_at IS NOT NULL AND submitted_at::date > due_date)
        ) AS not_completed_on_time,

        AVG(
            EXTRACT(EPOCH FROM (submitted_at - created_at)) / 3600
        ) FILTER (
            WHERE submitted_at IS NOT NULL
        ) AS avg_hours_to_complete
    FROM offender_checkin_v2
),

checkins_per_person AS (
    SELECT
        AVG(completed_count)::DECIMAL(10,2) AS avg_completed_checkins_per_person
    FROM (
        SELECT
            offender_id,
            COUNT(*) FILTER (
                WHERE status IN ('SUBMITTED', 'REVIEWED')
            ) AS completed_count
        FROM offender_checkin_v2
        GROUP BY offender_id
    ) t
    WHERE completed_count > 0
)

SELECT
    1 AS singleton,
    o.total_signed_up,
    o.active_users,
    o.inactive_users,
    c.completed_checkins,
    c.not_completed_on_time,
    ROUND(c.avg_hours_to_complete, 2) AS avg_hours_to_complete,
    cpp.avg_completed_checkins_per_person
FROM offenders o
CROSS JOIN checkins c
CROSS JOIN checkins_per_person cpp;

CREATE UNIQUE INDEX stats_summary_v1_singleton ON stats_summary_v1(singleton);