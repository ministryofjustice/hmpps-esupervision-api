-- liquibase formatted sql

-- changeset hmpps:84_update_monthly_stats_for_selected_event_deactivation splitStatements:false
-- Selected-event deactivations also reduce the active PoP count.
CREATE OR REPLACE FUNCTION refresh_monthly_stats(
    p_month DATE,
    p_start TIMESTAMPTZ,
    p_end   TIMESTAMPTZ
)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
  excluded_provider_code text := 'XXX';
BEGIN

WITH month_events AS (
  SELECT
    e.event_type,
    e.crn,
    e.time_to_submit_hours,
    COALESCE(e.provider_code, prev.provider_code) AS provider_code,
    COALESCE(e.provider_description, prev.provider_description) AS provider_description
  FROM event_audit_log_v2 e
  LEFT JOIN LATERAL (
    SELECT p.provider_code, p.provider_description
    FROM event_audit_log_v2 p
    WHERE e.provider_code IS NULL
      AND p.crn = e.crn
      AND p.provider_code IS NOT NULL
      AND p.occurred_at <= e.occurred_at
    ORDER BY p.occurred_at DESC, p.id DESC
    LIMIT 1
  ) prev ON TRUE
  WHERE e.occurred_at >= p_start
    AND e.occurred_at < p_end
),
providers_in_month AS (
  SELECT
    provider_code,
    COALESCE(MAX(provider_description), provider_code) AS provider_description
  FROM month_events
  WHERE provider_code IS NOT NULL
    AND provider_code <> excluded_provider_code
  GROUP BY provider_code
),
users_by_provider AS (
  SELECT
    provider_code,
    COUNT(*) FILTER (WHERE event_type IN ('SETUP_COMPLETED', 'OFFENDER_REACTIVATED')) AS users_activated,
    COUNT(*) FILTER (WHERE event_type IN ('OFFENDER_DEACTIVATED',
                                          'OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED',
                                          'OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS',
                                          'OFFENDER_AUTO_DEACTIVATED_SELECTED_EVENT_NO_LONGER_ACTIVE'))::BIGINT AS users_deactivated
  FROM month_events
  WHERE provider_code <> excluded_provider_code
  GROUP BY provider_code
),
checkins_by_provider AS (
  SELECT
    provider_code,
    COUNT(*) FILTER (WHERE event_type = 'CHECKIN_SUBMITTED')::BIGINT AS completed_checkins,
    COUNT(*) FILTER (WHERE event_type = 'CHECKIN_EXPIRED')::BIGINT AS not_completed_on_time,
    COALESCE(SUM(time_to_submit_hours) FILTER (WHERE event_type = 'CHECKIN_SUBMITTED'), 0)::NUMERIC AS total_hours_to_complete,
    COUNT(DISTINCT crn) FILTER (WHERE event_type = 'CHECKIN_SUBMITTED')::BIGINT AS unique_checkin_crns
  FROM month_events
  WHERE provider_code <> excluded_provider_code
  GROUP BY provider_code
),
rows_to_upsert AS (
  SELECT
    p_month AS month,
    p.provider_code,
    p.provider_description,
    COALESCE(u.users_activated, 0) AS users_activated,
    COALESCE(u.users_deactivated, 0) AS users_deactivated,
    COALESCE(c.completed_checkins, 0) AS completed_checkins,
    COALESCE(c.unique_checkin_crns, 0) AS unique_checkin_crns,
    COALESCE(c.not_completed_on_time, 0) AS not_completed_on_time,
    COALESCE(c.total_hours_to_complete, 0)::NUMERIC AS total_hours_to_complete,
    now() AS updated_at
  FROM providers_in_month p
  LEFT JOIN users_by_provider u ON u.provider_code = p.provider_code
  LEFT JOIN checkins_by_provider c ON c.provider_code = p.provider_code
)
INSERT INTO monthly_stats (
  month,
  provider_code,
  provider_description,
  users_activated,
  users_deactivated,
  completed_checkins,
  unique_checkin_crns,
  not_completed_on_time,
  total_hours_to_complete,
  updated_at
)
SELECT
  month,
  provider_code,
  provider_description,
  users_activated,
  users_deactivated,
  completed_checkins,
  unique_checkin_crns,
  not_completed_on_time,
  total_hours_to_complete,
  updated_at
FROM rows_to_upsert
ON CONFLICT (month, provider_code)
DO UPDATE SET
  provider_description = EXCLUDED.provider_description,
  users_activated = EXCLUDED.users_activated,
  users_deactivated = EXCLUDED.users_deactivated,
  completed_checkins = EXCLUDED.completed_checkins,
  unique_checkin_crns = EXCLUDED.unique_checkin_crns,
  not_completed_on_time = EXCLUDED.not_completed_on_time,
  total_hours_to_complete = EXCLUDED.total_hours_to_complete,
  updated_at = now();

END;
$$;