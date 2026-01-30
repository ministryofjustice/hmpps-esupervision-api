--liquibase formatted sql

--changeset rob.catton:21_create_refresh_monthly_stats_function splitStatements:false
CREATE OR REPLACE FUNCTION refresh_monthly_stats(
    p_month DATE,
    p_start TIMESTAMPTZ,
    p_end   TIMESTAMPTZ
)
RETURNS void
LANGUAGE sql
AS $$
WITH latest_user_state AS (
    SELECT DISTINCT ON (crn)
        crn,
        event_type
    FROM event_audit_log_v2
    WHERE event_type IN ('SETUP_COMPLETED', 'OFFENDER_DEACTIVATED')
    ORDER BY crn, occurred_at DESC
),

users AS (
    SELECT
        COUNT(*) FILTER (WHERE event_type = 'SETUP_COMPLETED')       AS users_activated,
        COUNT(*) FILTER (WHERE event_type = 'OFFENDER_DEACTIVATED')  AS users_deactivated
    FROM event_audit_log_v2
    WHERE occurred_at >= p_start AND occurred_at < p_end
      AND event_type IN ('SETUP_COMPLETED', 'OFFENDER_DEACTIVATED')
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
    SELECT SUM(completed_count) AS total_completed_checkins_per_offender
    FROM (
        SELECT
            crn,
            COUNT(*) FILTER (WHERE event_type IN ('CHECKIN_SUBMITTED', 'CHECKIN_REVIEWED')) AS completed_count
        FROM event_audit_log_v2
        WHERE occurred_at >= p_start AND occurred_at <  p_end
        GROUP BY crn
    ) t
)

INSERT INTO monthly_stats (
    month,
    users_activated,
    users_deactivated,
    completed_checkins,
    not_completed_on_time,
    total_hours_to_complete,
    total_completed_checkins_per_offender,
    updated_at
)
SELECT
    p_month,
    u.users_activated,
    u.users_deactivated,
    c.completed_checkins,
    c.not_completed_on_time,
    c.total_hours_to_complete,
    cpp.total_completed_checkins_per_offender,
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
    total_completed_checkins_per_offender = EXCLUDED.total_completed_checkins_per_offender,
    updated_at = now();
$$;
