--liquibase formatted sql

--changeset rob.catton:22_create_stats_summary_mv splitStatements:false
CREATE MATERIALIZED VIEW stats_summary_v1 AS
SELECT
    1 AS singleton,

    -- totals
    MAX(active_users)::BIGINT AS active_users,
    MAX(inactive_users)::BIGINT AS inactive_users,
    (MAX(active_users) + MAX(inactive_users))::BIGINT AS total_signed_up,

    -- monthly totals summed across months
    SUM(completed_checkins)::BIGINT AS completed_checkins,
    SUM(not_completed_on_time)::BIGINT AS not_completed_on_time,

    -- averages
    CASE
        WHEN SUM(completed_checkins) = 0 THEN 0
        ELSE ROUND(
            SUM(total_hours_to_complete) / SUM(completed_checkins)::NUMERIC,
            2
        )
    END AS avg_hours_to_complete,

    CASE
        WHEN (MAX(active_users) + MAX(inactive_users)) = 0 THEN 0
        ELSE ROUND(
            (SUM(total_completed_checkins_per_offender)::NUMERIC /
             (MAX(active_users) + MAX(inactive_users)))::NUMERIC,
            2
        )
    END AS avg_completed_checkins_per_person,

    MAX(updated_at) AS updated_at
FROM monthly_stats;

CREATE UNIQUE INDEX stats_summary_v1_singleton ON stats_summary_v1(singleton);
