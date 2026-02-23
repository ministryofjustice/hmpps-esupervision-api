--liquibase formatted sql

--changeset rob.catton:33_update_refresh_monthly_stats_for_pdu splitStatements:false
CREATE OR REPLACE FUNCTION refresh_monthly_stats(
    p_month DATE,
    p_start TIMESTAMPTZ,
    p_end   TIMESTAMPTZ
)
RETURNS void
LANGUAGE sql
AS $$
WITH pdus_in_month AS (
  SELECT DISTINCT
    pdu_code,
    pdu_description
  FROM event_audit_log_v2
  WHERE occurred_at >= p_start
    AND occurred_at <  p_end
    AND pdu_code IS NOT NULL
    AND pdu_description IS NOT NULL
),

users_by_pdu AS (
  SELECT
    pdu_code,
    COUNT(*) FILTER (WHERE event_type = 'SETUP_COMPLETED')      ::BIGINT AS users_activated,
    COUNT(*) FILTER (WHERE event_type = 'OFFENDER_DEACTIVATED') ::BIGINT AS users_deactivated
  FROM event_audit_log_v2
  WHERE occurred_at >= p_start
    AND occurred_at <  p_end
    AND event_type IN ('SETUP_COMPLETED', 'OFFENDER_DEACTIVATED')
  GROUP BY pdu_code
),

checkins_by_pdu AS (
  SELECT
    pdu_code,
    COUNT(*) FILTER (WHERE event_type = 'CHECKIN_SUBMITTED')::BIGINT AS completed_checkins,
    COUNT(*) FILTER (WHERE event_type = 'CHECKIN_EXPIRED')::BIGINT AS not_completed_on_time,
    COALESCE(SUM(time_to_submit_hours) FILTER (WHERE event_type = 'CHECKIN_SUBMITTED'), 0)::NUMERIC AS total_hours_to_complete
  FROM event_audit_log_v2
  WHERE occurred_at >= p_start
    AND occurred_at <  p_end
  GROUP BY pdu_code
),

unique_checkins_by_pdu AS (
  SELECT
    pdu_code,
    COUNT(DISTINCT crn) FILTER (WHERE event_type = 'CHECKIN_SUBMITTED')::BIGINT AS unique_checkin_crns
  FROM event_audit_log_v2
  WHERE occurred_at >= p_start
    AND occurred_at <  p_end
  GROUP BY pdu_code
),

rows_to_upsert AS (
  SELECT
    p_month AS month,
    p.pdu_code,
    p.pdu_description,

    COALESCE(u.users_activated, 0) AS users_activated,
    COALESCE(u.users_deactivated, 0) AS users_deactivated,

    COALESCE(c.completed_checkins, 0) AS completed_checkins,
    COALESCE(uc.unique_checkin_crns, 0) AS unique_checkin_crns,
    COALESCE(c.not_completed_on_time, 0) AS not_completed_on_time,
    COALESCE(c.total_hours_to_complete, 0)::NUMERIC AS total_hours_to_complete,

    now() AS updated_at
  FROM pdus_in_month p
  LEFT JOIN users_by_pdu u ON u.pdu_code = p.pdu_code
  LEFT JOIN checkins_by_pdu c ON c.pdu_code = p.pdu_code
  LEFT JOIN unique_checkins_by_pdu uc ON uc.pdu_code = p.pdu_code
)

INSERT INTO monthly_stats (
  month,
  pdu_code,
  pdu_description,
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
  pdu_code,
  pdu_description,
  users_activated,
  users_deactivated,
  completed_checkins,
  unique_checkin_crns,
  not_completed_on_time,
  total_hours_to_complete,
  updated_at
FROM rows_to_upsert
ON CONFLICT (month, pdu_code)
DO UPDATE SET
  pdu_description = EXCLUDED.pdu_description,
  users_activated = EXCLUDED.users_activated,
  users_deactivated = EXCLUDED.users_deactivated,
  completed_checkins = EXCLUDED.completed_checkins,
  unique_checkin_crns = EXCLUDED.unique_checkin_crns,
  not_completed_on_time = EXCLUDED.not_completed_on_time,
  total_hours_to_complete = EXCLUDED.total_hours_to_complete,
  updated_at = now();

$$;
