--liquibase formatted sql

--changeset rob.catton:22_create_stats_summary_mv splitStatements:false
CREATE MATERIALIZED VIEW stats_summary_v1 AS
SELECT
    1 AS singleton,

    -- totals
    SUM(total_signed_up)::BIGINT AS total_signed_up,
    SUM(active_users)::BIGINT AS active_users,
    SUM(completed_checkins)::BIGINT AS completed_checkins,
    SUM(not_completed_on_time)::BIGINT AS not_completed_on_time,

    -- derived
    (SUM(total_signed_up) - SUM(active_users))::BIGINT AS inactive_users,

    -- averages
    CASE
        WHEN SUM(completed_checkins) = 0 THEN 0
        ELSE ROUND(
            SUM(total_hours_to_complete) / SUM(completed_checkins)::NUMERIC,
            2
        )
    END AS avg_hours_to_complete,

    CASE
        WHEN SUM(active_users) = 0 THEN 0
        ELSE ROUND(
            SUM(total_completed_checkins_per_offender)::NUMERIC / SUM(active_users),
            2
        )
    END AS avg_completed_checkins_per_person,

    now() AS updated_at
FROM monthly_stats;

CREATE UNIQUE INDEX stats_summary_v1_singleton ON stats_summary_v1(singleton);


