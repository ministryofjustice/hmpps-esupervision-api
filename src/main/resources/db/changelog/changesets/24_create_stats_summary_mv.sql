--liquibase formatted sql

--changeset rob.catton:24_create_stats_summary_mv splitStatements:false
CREATE MATERIALIZED VIEW stats_summary_v1 AS
WITH running AS (
  SELECT
    month,
    users_activated,
    users_deactivated,
    updated_at,
    SUM(users_activated) OVER (ORDER BY month ASC) AS total_activated_to_date,
    SUM(users_deactivated) OVER (ORDER BY month ASC) AS total_deactivated_to_date,
    SUM(users_activated - users_deactivated) OVER (ORDER BY month ASC) AS active_users_to_date
  FROM monthly_stats
),
latest AS (
  SELECT *
  FROM running
  ORDER BY month DESC
  LIMIT 1
),
totals AS (
  SELECT
    COALESCE(SUM(completed_checkins), 0)::BIGINT AS completed_checkins,
    COALESCE(SUM(not_completed_on_time), 0)::BIGINT AS not_completed_on_time,
    COALESCE(SUM(total_hours_to_complete), 0)::NUMERIC AS total_hours_to_complete,
    COALESCE(SUM(unique_checkin_crns), 0)::BIGINT AS unique_checkin_crns,
    COALESCE(MAX(updated_at), 'epoch'::timestamptz) AS updated_at
  FROM monthly_stats
)
SELECT
  1 AS singleton,

  COALESCE(l.active_users_to_date, 0)::BIGINT AS users_activated,
  COALESCE(l.total_deactivated_to_date, 0)::BIGINT AS users_deactivated,
  COALESCE(l.total_activated_to_date, 0)::BIGINT AS total_signed_up,

  t.completed_checkins,
  t.not_completed_on_time,

  CASE
    WHEN t.completed_checkins = 0 THEN 0
    ELSE ROUND(t.total_hours_to_complete / t.completed_checkins::NUMERIC, 2)
  END AS avg_hours_to_complete,

  CASE
    WHEN t.unique_checkin_crns = 0 THEN 0
    ELSE ROUND(t.completed_checkins::NUMERIC / t.unique_checkin_crns::NUMERIC, 2)
  END AS avg_completed_checkins_per_person,

  CASE
    WHEN COALESCE(l.total_activated_to_date, 0) = 0 THEN 0
    ELSE ROUND(l.active_users_to_date::NUMERIC / l.total_activated_to_date::NUMERIC, 4)
  END AS pct_active_users,

  CASE
    WHEN COALESCE(l.total_activated_to_date, 0) = 0 THEN 0
    ELSE ROUND(l.total_deactivated_to_date::NUMERIC / l.total_activated_to_date::NUMERIC, 4)
  END AS pct_inactive_users,

  CASE
    WHEN (t.completed_checkins + t.not_completed_on_time) = 0 THEN 0
    ELSE ROUND(t.completed_checkins::NUMERIC / (t.completed_checkins + t.not_completed_on_time)::NUMERIC, 4)
  END AS pct_completed_checkins,

  CASE
    WHEN (t.completed_checkins + t.not_completed_on_time) = 0 THEN 0
    ELSE ROUND(t.not_completed_on_time::NUMERIC / (t.completed_checkins + t.not_completed_on_time)::NUMERIC, 4)
  END AS pct_expired_checkins,

  GREATEST(t.updated_at, COALESCE(l.updated_at, 'epoch'::timestamptz)) AS updated_at
FROM totals t
CROSS JOIN latest l;

CREATE UNIQUE INDEX stats_summary_v1_singleton ON stats_summary_v1(singleton);
