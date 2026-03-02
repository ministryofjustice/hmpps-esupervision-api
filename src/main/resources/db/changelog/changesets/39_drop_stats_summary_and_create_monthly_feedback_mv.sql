--liquibase formatted sql

--changeset rob.catton:39_drop_stats_summary_and_create_monthly_feedback_mv splitStatements:false
DROP MATERIALIZED VIEW IF EXISTS stats_summary_v1; -- No longer needed
DROP MATERIALIZED VIEW IF EXISTS total_feedback_monthly;

CREATE MATERIALIZED VIEW total_feedback_monthly AS
WITH
-- Latest feedback version present in raw feedback table (defaults to 1)
feedback_version_overall AS (
  SELECT COALESCE(MAX((f.feedback->>'version')::int), 1) AS feedback_version
  FROM feedback f
),

-- TRUE total feedback submissions per month for the latest version (key-independent)
month_totals AS (
  SELECT
    date_trunc('month', f.created_at)::date AS month,
    COUNT(*)::bigint AS feedback_total
  FROM feedback f
  JOIN feedback_version_overall fvo
    ON COALESCE((f.feedback->>'version')::int, 1) = fvo.feedback_version
  GROUP BY 1
),

-- Expand counts JSON into rows (still sourced from monthly_feedback_stats, filtered to latest version)
counts_expanded AS (
  SELECT
    mfs.month,
    mfs.feedback_key,
    e.key AS option_key,
    SUM((e.value)::bigint)::bigint AS option_count
  FROM monthly_feedback_stats mfs
  JOIN feedback_version_overall fvo
    ON fvo.feedback_version = mfs.feedback_version
  CROSS JOIN LATERAL jsonb_each(mfs.counts) AS e(key, value)
  GROUP BY mfs.month, mfs.feedback_key, e.key
),

-- Re-aggregate expanded counts back to JSON per (month, key)
counts_by_month_key AS (
  SELECT
    month,
    feedback_key,
    COALESCE(jsonb_object_agg(option_key, option_count), '{}'::jsonb) AS counts
  FROM counts_expanded
  GROUP BY month, feedback_key
),

-- Compute pct per (month, key) using:
-- denom = feedback_total - notAnswered (min 1), and exclude notAnswered from pct JSON
pct_by_month_key AS (
  SELECT
    cbmk.month,
    cbmk.feedback_key,
    COALESCE(
      jsonb_object_agg(
        e.key,
        ROUND(
          (e.value)::bigint::numeric
          / GREATEST(
              (
                mt.feedback_total
                - COALESCE((cbmk.counts->>'notAnswered')::bigint, 0)
              )::numeric,
              1
            ),
          4
        )
      ),
      '{}'::jsonb
    ) AS pct
  FROM counts_by_month_key cbmk
  JOIN month_totals mt ON mt.month = cbmk.month
  CROSS JOIN LATERAL jsonb_each(cbmk.counts) AS e(key, value)
  WHERE e.key <> 'notAnswered'
  GROUP BY cbmk.month, cbmk.feedback_key, mt.feedback_total, cbmk.counts
)

SELECT
  mt.month,
  mt.feedback_total,

  COALESCE(
    (SELECT cbmk.counts
     FROM counts_by_month_key cbmk
     WHERE cbmk.month = mt.month AND cbmk.feedback_key = 'howEasy'),
    '{}'::jsonb
  ) AS how_easy_counts,

  COALESCE(
    (SELECT pbmk.pct
     FROM pct_by_month_key pbmk
     WHERE pbmk.month = mt.month AND pbmk.feedback_key = 'howEasy'),
    '{}'::jsonb
  ) AS how_easy_pct,

  COALESCE(
    (SELECT cbmk.counts
     FROM counts_by_month_key cbmk
     WHERE cbmk.month = mt.month AND cbmk.feedback_key = 'gettingSupport'),
    '{}'::jsonb
  ) AS getting_support_counts,

  COALESCE(
    (SELECT pbmk.pct
     FROM pct_by_month_key pbmk
     WHERE pbmk.month = mt.month AND pbmk.feedback_key = 'gettingSupport'),
    '{}'::jsonb
  ) AS getting_support_pct,

  COALESCE(
    (SELECT cbmk.counts
     FROM counts_by_month_key cbmk
     WHERE cbmk.month = mt.month AND cbmk.feedback_key = 'improvements'),
    '{}'::jsonb
  ) AS improvements_counts,

  COALESCE(
    (SELECT pbmk.pct
     FROM pct_by_month_key pbmk
     WHERE pbmk.month = mt.month AND pbmk.feedback_key = 'improvements'),
    '{}'::jsonb
  ) AS improvements_pct

FROM month_totals mt
ORDER BY mt.month;

CREATE UNIQUE INDEX total_feedback_monthly_month_ux
  ON total_feedback_monthly(month);

CREATE INDEX total_feedback_monthly_month_idx
  ON total_feedback_monthly(month);