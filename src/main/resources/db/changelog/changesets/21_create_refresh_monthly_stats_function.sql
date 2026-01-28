--liquibase formatted sql

--changeset rob.catton:21_create_refresh_monthly_stats_function splitStatements:false
CREATE OR REPLACE FUNCTION refresh_monthly_stats(p_month DATE)
RETURNS void
LANGUAGE sql
AS $$
WITH offenders AS (
    -- rolling totals from offender_v2
    SELECT
        COUNT(*) FILTER (WHERE status <> 'INITIAL') AS total_signed_up,
        COUNT(*) FILTER (WHERE status = 'VERIFIED') AS active_users
    FROM offender_v2
),

checkins AS (
    -- only check-ins from event_audit_log_v2 for the given month
    SELECT
        COUNT(*) FILTER (WHERE event_type IN ('CHECKIN_SUBMITTED', 'CHECKIN_REVIEWED')) AS completed_checkins,
        COUNT(*) FILTER (WHERE event_type = 'CHECKIN_EXPIRED') AS not_completed_on_time,
        SUM(time_to_submit_hours) FILTER (WHERE event_type = 'CHECKIN_SUBMITTED') AS total_hours_to_complete
    FROM event_audit_log_v2
    WHERE date_trunc('month', occurred_at)::date = p_month
),

checkins_per_person AS (
    SELECT SUM(completed_count) AS total_completed_checkins_per_offender
    FROM (
        SELECT
            crn,
            COUNT(*) FILTER (WHERE event_type IN ('CHECKIN_SUBMITTED', 'CHECKIN_REVIEWED')) AS completed_count
        FROM event_audit_log_v2
        WHERE date_trunc('month', occurred_at)::date = p_month
        GROUP BY crn
    ) t
)

INSERT INTO monthly_stats (
    month,
    total_signed_up,
    active_users,
    completed_checkins,
    not_completed_on_time,
    total_hours_to_complete,
    total_completed_checkins_per_offender,
    updated_at
)
SELECT
    p_month,
    o.total_signed_up,
    o.active_users,
    c.completed_checkins,
    c.not_completed_on_time,
    c.total_hours_to_complete,
    cpp.total_completed_checkins_per_offender,
    now()
FROM offenders o
CROSS JOIN checkins c
CROSS JOIN checkins_per_person cpp
ON CONFLICT (month)
DO UPDATE SET
    total_signed_up = EXCLUDED.total_signed_up,
    active_users = EXCLUDED.active_users,
    completed_checkins = EXCLUDED.completed_checkins,
    not_completed_on_time = EXCLUDED.not_completed_on_time,
    total_hours_to_complete = EXCLUDED.total_hours_to_complete,
    total_completed_checkins_per_offender = EXCLUDED.total_completed_checkins_per_offender,
    updated_at = now();
$$;
