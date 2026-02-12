--liquibase formatted sql

--changeset rob.catton:26_update_stats_summary_mv_for_feedback_table splitStatements:false
DROP MATERIALIZED VIEW IF EXISTS stats_summary_v1;

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
),

/* ===========================
   CHANGE START: feedback roll-up across ALL months
   Strategy:
   - pick ONE feedback_version for the holistic view (latest version present overall)
   - sum totals across months for that version (denominator)
   - sum counts JSON across months for that version
   - compute pct JSON as summed_count / summed_total
   =========================== */

feedback_version_overall AS (
  SELECT COALESCE(MAX(feedback_version), 1) AS feedback_version
  FROM monthly_feedback_stats
),

feedback_totals AS (
  SELECT COALESCE(SUM(month_total), 0)::BIGINT AS feedback_total
  FROM (
    SELECT mfs.month, MAX(mfs.total)::BIGINT AS month_total
    FROM monthly_feedback_stats mfs
    JOIN feedback_version_overall fvo ON fvo.feedback_version = mfs.feedback_version
    GROUP BY mfs.month
  ) t
),

how_easy_counts AS (
  SELECT COALESCE(jsonb_object_agg(k, v), '{}'::jsonb) AS counts
  FROM (
    SELECT
      e.key AS k,
      SUM((e.value)::BIGINT)::BIGINT AS v
    FROM monthly_feedback_stats mfs
    JOIN feedback_version_overall fvo ON fvo.feedback_version = mfs.feedback_version
    CROSS JOIN LATERAL jsonb_each(mfs.counts) AS e(key, value)
    WHERE mfs.feedback_key = 'howEasy'
    GROUP BY e.key
  ) x
),

getting_support_counts AS (
  SELECT COALESCE(jsonb_object_agg(k, v), '{}'::jsonb) AS counts
  FROM (
    SELECT e.key AS k, SUM((e.value)::BIGINT)::BIGINT AS v
    FROM monthly_feedback_stats mfs
    JOIN feedback_version_overall fvo ON fvo.feedback_version = mfs.feedback_version
    CROSS JOIN LATERAL jsonb_each(mfs.counts) AS e(key, value)
    WHERE mfs.feedback_key = 'gettingSupport'
    GROUP BY e.key
  ) x
),

improvements_counts AS (
  SELECT COALESCE(jsonb_object_agg(k, v), '{}'::jsonb) AS counts
  FROM (
    SELECT e.key AS k, SUM((e.value)::BIGINT)::BIGINT AS v
    FROM monthly_feedback_stats mfs
    JOIN feedback_version_overall fvo ON fvo.feedback_version = mfs.feedback_version
    CROSS JOIN LATERAL jsonb_each(mfs.counts) AS e(key, value)
    WHERE mfs.feedback_key = 'improvements'
    GROUP BY e.key
  ) x
),

how_easy_pct AS (
  SELECT COALESCE(
    jsonb_object_agg(
      e.key,
      ROUND(
        (e.value)::BIGINT::NUMERIC
        / GREATEST(
            (
              (SELECT feedback_total FROM feedback_totals)
              - COALESCE((hec.counts->>'notAnswered')::BIGINT, 0)
            ),
            1
          )::NUMERIC,
        4
      )
    ),
    '{}'::jsonb
  ) AS pct
  FROM how_easy_counts hec
  CROSS JOIN LATERAL jsonb_each(hec.counts) AS e(key, value)
  WHERE e.key <> 'notAnswered'
),

getting_support_pct AS (
  SELECT COALESCE(
    jsonb_object_agg(
      e.key,
      ROUND(
        (e.value)::BIGINT::NUMERIC
        / GREATEST(
            (
              (SELECT feedback_total FROM feedback_totals)
              - COALESCE((gsc.counts->>'notAnswered')::BIGINT, 0)
            ),
            1
          )::NUMERIC,
        4
      )
    ),
    '{}'::jsonb
  ) AS pct
  FROM getting_support_counts gsc
  CROSS JOIN LATERAL jsonb_each(gsc.counts) AS e(key, value)
  WHERE e.key <> 'notAnswered'
),

improvements_pct AS (
  SELECT COALESCE(
    jsonb_object_agg(
      e.key,
      ROUND(
        (e.value)::BIGINT::NUMERIC
        / GREATEST(
            (
              (SELECT feedback_total FROM feedback_totals)
              - COALESCE((ic.counts->>'notAnswered')::BIGINT, 0)
            ),
            1
          )::NUMERIC,
        4
      )
    ),
    '{}'::jsonb
  ) AS pct
  FROM improvements_counts ic
  CROSS JOIN LATERAL jsonb_each(ic.counts) AS e(key, value)
  WHERE e.key <> 'notAnswered'
)

/* ===========================
   CHANGE END
   =========================== */

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

  /* CHANGE START: holistic feedback across ALL months (only for highest feedback version) */
  COALESCE((SELECT feedback_total FROM feedback_totals), 0)::BIGINT AS feedback_total,

  COALESCE((SELECT counts FROM how_easy_counts), '{}'::jsonb) AS how_easy_counts,
  COALESCE((SELECT pct FROM how_easy_pct), '{}'::jsonb) AS how_easy_pct,

  COALESCE((SELECT counts FROM getting_support_counts), '{}'::jsonb) AS getting_support_counts,
  COALESCE((SELECT pct FROM getting_support_pct), '{}'::jsonb) AS getting_support_pct,

  COALESCE((SELECT counts FROM improvements_counts), '{}'::jsonb) AS improvements_counts,
  COALESCE((SELECT pct FROM improvements_pct), '{}'::jsonb) AS improvements_pct,
  /* CHANGE END */

  GREATEST(t.updated_at, COALESCE(l.updated_at, 'epoch'::timestamptz)) AS updated_at
FROM totals t
CROSS JOIN latest l;

CREATE UNIQUE INDEX stats_summary_v1_singleton ON stats_summary_v1(singleton);
