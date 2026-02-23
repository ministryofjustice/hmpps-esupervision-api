--liquibase formatted sql

--changeset rob.catton:36_create_stats_summary_provider_month_mv splitStatements:false
DROP MATERIALIZED VIEW IF EXISTS stats_summary_provider_month_v1;

-- Rows:
--  - one per (month, provider_code) with row_type='PROVIDER'
--  - one per month with row_type='ALL' (monthly totals, correctly weighted averages)
CREATE MATERIALIZED VIEW stats_summary_provider_month_v1 AS
WITH
/* -------- per-provider/month base (monthly_stats is already per month+provider, but SUM is harmless) -------- */
provider_base AS (
  SELECT
    month,
    provider_code,
    MIN(provider_description) AS provider_description,

    SUM(users_activated)::BIGINT AS users_activated,
    SUM(users_deactivated)::BIGINT AS users_deactivated,
    SUM(completed_checkins)::BIGINT AS completed_checkins,
    SUM(not_completed_on_time)::BIGINT AS not_completed_on_time,
    SUM(COALESCE(unique_checkin_crns, 0))::BIGINT AS unique_checkin_crns,
    COALESCE(SUM(total_hours_to_complete), 0)::NUMERIC AS total_hours_to_complete,

    MAX(updated_at) AS updated_at
  FROM monthly_stats
  GROUP BY month, provider_code
),

provider_running AS (
  SELECT
    *,
    SUM(users_activated) OVER (PARTITION BY provider_code ORDER BY month ASC) AS total_activated_to_date,
    SUM(users_deactivated) OVER (PARTITION BY provider_code ORDER BY month ASC) AS total_deactivated_to_date,
    SUM(users_activated - users_deactivated) OVER (PARTITION BY provider_code ORDER BY month ASC) AS active_users_to_date
  FROM provider_base
),

/* -------- ALL/month base: sum across providers for the month (this gives correct denominators) -------- */
all_base AS (
  SELECT
    month,
    SUM(users_activated)::BIGINT AS users_activated,
    SUM(users_deactivated)::BIGINT AS users_deactivated,
    SUM(completed_checkins)::BIGINT AS completed_checkins,
    SUM(not_completed_on_time)::BIGINT AS not_completed_on_time,
    SUM(COALESCE(unique_checkin_crns, 0))::BIGINT AS unique_checkin_crns,
    COALESCE(SUM(total_hours_to_complete), 0)::NUMERIC AS total_hours_to_complete,
    MAX(updated_at) AS updated_at
  FROM provider_base
  GROUP BY month
),

all_running AS (
  SELECT
    *,
    SUM(users_activated) OVER (ORDER BY month ASC) AS total_activated_to_date,
    SUM(users_deactivated) OVER (ORDER BY month ASC) AS total_deactivated_to_date,
    SUM(users_activated - users_deactivated) OVER (ORDER BY month ASC) AS active_users_to_date
  FROM all_base
)

/* -------- FINAL: ALL rows + PROVIDER rows -------- */
SELECT
  'ALL'::text AS row_type,
  ar.month,
  NULL::varchar(10) AS provider_code,
  NULL::varchar(255) AS provider_description,

  -- snapshot totals "as of this month"
  COALESCE(ar.active_users_to_date, 0)::BIGINT AS active_users,
  COALESCE(ar.total_deactivated_to_date, 0)::BIGINT AS inactive_users,
  COALESCE(ar.total_activated_to_date, 0)::BIGINT AS total_signed_up,

  -- month totals
  COALESCE(ar.completed_checkins, 0)::BIGINT AS completed_checkins,
  COALESCE(ar.not_completed_on_time, 0)::BIGINT AS not_completed_on_time,

  -- month averages (CORRECTLY weighted by completed_checkins / unique_checkin_crns)
  CASE
    WHEN COALESCE(ar.completed_checkins, 0) = 0 THEN 0
    ELSE ROUND(ar.total_hours_to_complete / ar.completed_checkins::NUMERIC, 2)
  END AS avg_hours_to_complete,

  CASE
    WHEN COALESCE(ar.unique_checkin_crns, 0) = 0 THEN 0
    ELSE ROUND(ar.completed_checkins::NUMERIC / ar.unique_checkin_crns::NUMERIC, 2)
  END AS avg_completed_checkins_per_person,

  -- snapshot percentages "as of this month"
  CASE
    WHEN COALESCE(ar.total_activated_to_date, 0) = 0 THEN 0
    ELSE ROUND(ar.active_users_to_date::NUMERIC / ar.total_activated_to_date::NUMERIC, 4)
  END AS pct_active_users,

  CASE
    WHEN COALESCE(ar.total_activated_to_date, 0) = 0 THEN 0
    ELSE ROUND(ar.total_deactivated_to_date::NUMERIC / ar.total_activated_to_date::NUMERIC, 4)
  END AS pct_inactive_users,

  -- month percentages
  CASE
    WHEN (COALESCE(ar.completed_checkins, 0) + COALESCE(ar.not_completed_on_time, 0)) = 0 THEN 0
    ELSE ROUND(
      ar.completed_checkins::NUMERIC
      / (ar.completed_checkins + ar.not_completed_on_time)::NUMERIC,
      4
    )
  END AS pct_completed_checkins,

  CASE
    WHEN (COALESCE(ar.completed_checkins, 0) + COALESCE(ar.not_completed_on_time, 0)) = 0 THEN 0
    ELSE ROUND(
      ar.not_completed_on_time::NUMERIC
      / (ar.completed_checkins + ar.not_completed_on_time)::NUMERIC,
      4
    )
  END AS pct_expired_checkins,

  ar.updated_at
FROM all_running ar

UNION ALL

SELECT
  'PROVIDER'::text AS row_type,
  pr.month,
  pr.provider_code,
  pr.provider_description,

  -- snapshot totals "as of this month"
  COALESCE(pr.active_users_to_date, 0)::BIGINT AS active_users,
  COALESCE(pr.total_deactivated_to_date, 0)::BIGINT AS inactive_users,
  COALESCE(pr.total_activated_to_date, 0)::BIGINT AS total_signed_up,

  -- month totals
  COALESCE(pr.completed_checkins, 0)::BIGINT AS completed_checkins,
  COALESCE(pr.not_completed_on_time, 0)::BIGINT AS not_completed_on_time,

  -- month averages
  CASE
    WHEN COALESCE(pr.completed_checkins, 0) = 0 THEN 0
    ELSE ROUND(pr.total_hours_to_complete / pr.completed_checkins::NUMERIC, 2)
  END AS avg_hours_to_complete,

  CASE
    WHEN COALESCE(pr.unique_checkin_crns, 0) = 0 THEN 0
    ELSE ROUND(pr.completed_checkins::NUMERIC / pr.unique_checkin_crns::NUMERIC, 2)
  END AS avg_completed_checkins_per_person,

  -- snapshot percentages "as of this month"
  CASE
    WHEN COALESCE(pr.total_activated_to_date, 0) = 0 THEN 0
    ELSE ROUND(pr.active_users_to_date::NUMERIC / pr.total_activated_to_date::NUMERIC, 4)
  END AS pct_active_users,

  CASE
    WHEN COALESCE(pr.total_activated_to_date, 0) = 0 THEN 0
    ELSE ROUND(pr.total_deactivated_to_date::NUMERIC / pr.total_activated_to_date::NUMERIC, 4)
  END AS pct_inactive_users,

  -- month percentages
  CASE
    WHEN (COALESCE(pr.completed_checkins, 0) + COALESCE(pr.not_completed_on_time, 0)) = 0 THEN 0
    ELSE ROUND(
      pr.completed_checkins::NUMERIC
      / (pr.completed_checkins + pr.not_completed_on_time)::NUMERIC,
      4
    )
  END AS pct_completed_checkins,

  CASE
    WHEN (COALESCE(pr.completed_checkins, 0) + COALESCE(pr.not_completed_on_time, 0)) = 0 THEN 0
    ELSE ROUND(
      pr.not_completed_on_time::NUMERIC
      / (pr.completed_checkins + pr.not_completed_on_time)::NUMERIC,
      4
    )
  END AS pct_expired_checkins,

  pr.updated_at
FROM provider_running pr
;

-- Indexes for range queries + CONCURRENT refresh safety
CREATE UNIQUE INDEX stats_summary_provider_month_v1_unique_row
  ON stats_summary_provider_month_v1(row_type, month, provider_code);

CREATE INDEX stats_summary_provider_month_v1_month_idx
  ON stats_summary_provider_month_v1(month);

CREATE INDEX stats_summary_provider_month_v1_provider_idx
  ON stats_summary_provider_month_v1(provider_code);

CREATE INDEX stats_summary_provider_month_v1_row_type_idx
  ON stats_summary_provider_month_v1(row_type);
