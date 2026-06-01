--liquibase formatted sql

--changeset Maddie-Williams:31_update_refresh_monthly_stats_function_include_reactivations splitStatements:false
CREATE OR REPLACE FUNCTION refresh_monthly_stats(
    p_month DATE,
    p_start TIMESTAMPTZ,
    p_end   TIMESTAMPTZ
)
RETURNS void
LANGUAGE sql
AS $$
WITH users AS (
    SELECT
        COUNT(*) FILTER (WHERE event_type IN ('SETUP_COMPLETED', 'OFFENDER_REACTIVATED')) AS users_activated,
        COUNT(*) FILTER (WHERE event_type = 'OFFENDER_DEACTIVATED')  AS users_deactivated
    FROM event_audit_log_v2
    WHERE occurred_at >= p_start AND occurred_at < p_end
      AND event_type IN ('SETUP_COMPLETED', 'OFFENDER_REACTIVATED', 'OFFENDER_DEACTIVATED')
),

checkins AS (
    SELECT
        COUNT(*) FILTER (WHERE event_type IN ('CHECKIN_SUBMITTED', 'CHECKIN_REVIEWED')) AS completed_checkins,
        COUNT(*) FILTER (WHERE event_type = 'CHECKIN_EXPIRED') AS not_completed_on_time,
        SUM(time_to_submit_hours) FILTER (WHERE event_type = 'CHECKIN_SUBMITTED') AS total_hours_to_complete
    FROM event_audit_log_v2 
    WHERE occurred_at >= p_start AND occurred_at <  p_end
),

checkins_per_person AS (
    SELECT
        COUNT(DISTINCT crn) FILTER (WHERE event_type IN ('CHECKIN_SUBMITTED', 'CHECKIN_REVIEWED')) AS unique_checkin_crns
    FROM event_audit_log_v2
    WHERE occurred_at >= p_start AND occurred_at < p_end
)

INSERT INTO monthly_stats (
    month,
    users_activated,
    users_deactivated,
    completed_checkins,
    not_completed_on_time,
    total_hours_to_complete,
    unique_checkin_crns,
    updated_at
)
SELECT
    p_month,
    u.users_activated,
    u.users_deactivated,
    c.completed_checkins,
    c.not_completed_on_time,
    c.total_hours_to_complete,
    cpp.unique_checkin_crns,
    now()
FROM users u
CROSS JOIN checkins c
CROSS JOIN checkins_per_person cpp
ON CONFLICT (month)
DO UPDATE SET
    users_activated = EXCLUDED.users_activated,
    users_deactivated = EXCLUDED.users_deactivated,
    completed_checkins = EXCLUDED.completed_checkins,
    not_completed_on_time = EXCLUDED.not_completed_on_time,
    total_hours_to_complete = EXCLUDED.total_hours_to_complete,
    updated_at = now();
$$;
