--liquibase formatted sql

--changeset rob.catton:39_drop_stats_summary_and_create_monthly_feedback_mv-1 splitStatements:false
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
    mt.feedback_total as feedback_total,

    COALESCE(MAX(cbmk.counts::text) FILTER (WHERE cbmk.feedback_key = 'howEasy'), '{}')::jsonb AS how_easy_counts,
    COALESCE(MAX(pbmk.pct::text)    FILTER (WHERE pbmk.feedback_key = 'howEasy'), '{}')::jsonb AS how_easy_pct,

    COALESCE(MAX(cbmk.counts::text) FILTER (WHERE cbmk.feedback_key = 'gettingSupport'), '{}')::jsonb AS getting_support_counts,
    COALESCE(MAX(pbmk.pct::text)    FILTER (WHERE pbmk.feedback_key = 'gettingSupport'), '{}')::jsonb AS getting_support_pct,

    COALESCE(MAX(cbmk.counts::text) FILTER (WHERE cbmk.feedback_key = 'improvements'), '{}')::jsonb AS improvements_counts,
    COALESCE(MAX(pbmk.pct::text)    FILTER (WHERE pbmk.feedback_key = 'improvements'), '{}')::jsonb AS improvements_pct

FROM month_totals mt
LEFT JOIN counts_by_month_key cbmk ON cbmk.month = mt.month
LEFT JOIN pct_by_month_key pbmk    ON pbmk.month = mt.month AND pbmk.feedback_key = cbmk.feedback_key
GROUP BY mt.month, mt.feedback_total
ORDER BY mt.month;

CREATE UNIQUE INDEX total_feedback_monthly_month_ux
  ON total_feedback_monthly(month);

CREATE INDEX total_feedback_monthly_month_idx
  ON total_feedback_monthly(month);

--changeset rob.catton:39_drop_stats_summary_and_create_monthly_feedback_mv-2 splitStatements:false

CREATE OR REPLACE FUNCTION feedback_calc_pct(counts JSONB, total BIGINT, not_answered BIGINT)
    RETURNS JSONB AS $$
SELECT COALESCE(
               jsonb_object_agg(k, ROUND(v::numeric / GREATEST(total - not_answered, 1), 4)),
               '{}'::jsonb
       )
FROM jsonb_each_text(counts) t(k, v)
WHERE k <> 'notAnswered';
$$ LANGUAGE sql IMMUTABLE;

--changeset rob.catton:39_drop_stats_summary_and_create_monthly_feedback_mv-3 splitStatements:f

CREATE OR REPLACE FUNCTION get_total_feedback_summary(from_month DATE, to_month DATE)
    -- from_month (inclusive) and to_month (exclusive)
RETURNS TABLE (
                  feedback_total BIGINT,
                  how_easy_counts JSONB,
                  how_easy_pct JSONB,
                  getting_support_counts JSONB,
                  getting_support_pct JSONB,
                  improvements_counts JSONB,
                  improvements_pct JSONB
              ) AS $$
BEGIN
    RETURN QUERY
    WITH combined_counts AS (
        -- aggregate everything into one row
        SELECT
            COALESCE(SUM(total_feedback_monthly.feedback_total), 0)::BIGINT as total,
            (SELECT jsonb_object_agg(key, val) FROM (
                SELECT key, SUM((value)::bigint) val FROM total_feedback_monthly t, jsonb_each(t.how_easy_counts)
                GROUP BY key) s
            ) as easy_counts,
            (SELECT jsonb_object_agg(key, val) FROM (
                SELECT key, SUM((value)::bigint) val FROM total_feedback_monthly t, jsonb_each(t.getting_support_counts)
                WHERE month >= from_month AND month < to_month GROUP BY key) s
            ) as support_counts,
            (SELECT jsonb_object_agg(key, val) FROM (
                SELECT key, SUM((value)::bigint) val FROM total_feedback_monthly t, jsonb_each(t.improvements_counts)
                WHERE month >= from_month AND month < to_month GROUP BY key) s
            ) as imp_counts
        FROM total_feedback_monthly
        WHERE month >= from_month AND month < to_month
    ),
         calculations AS (
             SELECT
                 c.total as feedback_total,
                 COALESCE(c.easy_counts, '{}'::jsonb) as easy_counts,
                 COALESCE((SELECT SUM(v::bigint) FROM jsonb_each_text(c.easy_counts) t(k,v)), 0) AS easy_total,
                 COALESCE((c.easy_counts->>'notAnswered')::bigint, 0) AS easy_na,

                 COALESCE(c.support_counts, '{}'::jsonb) as support_counts,
                 COALESCE((SELECT SUM(v::bigint) FROM jsonb_each_text(c.support_counts) t(k,v)), 0) AS support_total,
                 COALESCE((c.support_counts->>'notAnswered')::bigint, 0) AS support_na,

                 COALESCE(c.imp_counts, '{}'::jsonb) as imp_counts,
                 COALESCE((SELECT SUM(v::bigint) FROM jsonb_each_text(c.imp_counts) t(k,v)), 0) AS imp_total,
                 COALESCE((c.imp_counts->>'notAnswered')::bigint, 0) AS imp_na
             FROM combined_counts c
         )
    SELECT
        calc.feedback_total,
        calc.easy_counts,
        feedback_calc_pct(calc.easy_counts, calc.easy_total::BIGINT, calc.easy_na::BIGINT),
        calc.support_counts,
        feedback_calc_pct(calc.support_counts, calc.support_total::BIGINT, calc.support_na::BIGINT),
        calc.imp_counts,
        feedback_calc_pct(calc.imp_counts, calc.imp_total::BIGINT, calc.imp_na::BIGINT)
    FROM calculations calc;
END;
$$ LANGUAGE plpgsql STABLE;

--changeset rob.catton:39_drop_stats_summary_and_create_monthly_feedback_mv-4 splitStatements:f

CREATE OR REPLACE FUNCTION get_summary(from_month DATE, to_month DATE, p_row_type TEXT DEFAULT 'ALL')
    RETURNS TABLE (
                      provider_code VARCHAR(10),
                      provider_description VARCHAR(255),
                      total_signed_up BIGINT,
                      active_users BIGINT,
                      inactive_users BIGINT,
                      completed_checkins BIGINT,
                      expired_checkins BIGINT,
                      avg_hours_to_complete DECIMAL,
                      avg_checkins_completed_per_person DECIMAL,
                      pct_active_users DECIMAL,
                      pct_inactive_users DECIMAL,
                      pct_completed_checkins DECIMAL,
                      pct_expired_checkins DECIMAL,
                      pct_signed_up DECIMAL,
                      updated_at TIMESTAMP WITH TIME ZONE
                  ) AS $$
BEGIN
    RETURN QUERY
        WITH range_stats AS (
            SELECT
                COALESCE(s.provider_code, 'ALL') as provider_code,
                COALESCE(SUM(s.completed_checkins), 0)::BIGINT AS range_completed,
                COALESCE(SUM(s.not_completed_on_time), 0)::BIGINT AS range_expired,
                COALESCE(SUM(s.total_hours_to_complete), 0)::NUMERIC AS range_hours,
                COALESCE(SUM(s.unique_checkin_crns), 0)::BIGINT AS range_unique_crns
            FROM (SELECT 1) dummy
            LEFT JOIN stats_summary_provider_month s ON s.month >= from_month AND s.month < to_month AND s.row_type = p_row_type
            GROUP BY s.provider_code
        ),
        latest_months AS (
             -- Identify the latest month for each provider in the range
             SELECT
                 s.provider_code,
                 MAX(s.month) as max_month
             FROM stats_summary_provider_month s
             WHERE s.month >= from_month AND s.month < to_month
               AND s.row_type = p_row_type
             GROUP BY s.provider_code
        ),
        latest_total as (
            SELECT COALESCE(MAX(s.total_signed_up), 0) as total_signed_up
            FROM (
                SELECT s.total_signed_up
                FROM stats_summary_provider_month s
                WHERE row_type = 'ALL' AND s.month >= from_month AND s.month < to_month
                ORDER BY month DESC
                LIMIT 1
            ) s
        ),
        latest_snapshot AS (
             SELECT
                 rs.provider_code,
                 COALESCE(s.provider_description, '') as provider_description,
                 COALESCE(s.total_signed_up, 0) as total_signed_up,
                 COALESCE(s.active_users, 0) as active_users,
                 COALESCE(s.inactive_users, 0) as inactive_users,
                 COALESCE(s.pct_active_users, 0) as pct_active_users,
                 COALESCE(s.pct_inactive_users, 0) as pct_inactive_users,
                 COALESCE(s.updated_at, now()) as updated_at
             FROM range_stats rs
             LEFT JOIN latest_months lm ON rs.provider_code = lm.provider_code
             LEFT JOIN stats_summary_provider_month s ON s.provider_code = lm.provider_code AND s.month = lm.max_month AND s.row_type = p_row_type
        )
        SELECT
            rs.provider_code,
            ls.provider_description,
            ls.total_signed_up as total_signed_up,
            ls.active_users,
            ls.inactive_users,
            rs.range_completed as completed_checkins,
            rs.range_expired as expired_checkins,
            CASE WHEN rs.range_completed = 0 THEN 0
                ELSE ROUND(rs.range_hours / rs.range_completed, 4) END as avg_hours_to_complete,
            CASE WHEN rs.range_unique_crns = 0 THEN 0
                ELSE ROUND(rs.range_completed::NUMERIC / rs.range_unique_crns, 4) END as avg_checkins_completed_per_person,
            ls.pct_active_users,
            ls.pct_inactive_users,
            CASE WHEN (rs.range_completed + rs.range_expired) = 0 THEN 0
                 ELSE ROUND(rs.range_completed::NUMERIC / (rs.range_completed + rs.range_expired), 4) END as pct_completed_checkins,
            CASE WHEN (rs.range_completed + rs.range_expired) = 0 THEN 0
                 ELSE ROUND(rs.range_expired::NUMERIC / (rs.range_completed + rs.range_expired), 4) END as pct_expired_checkins,
            CASE WHEN lt.total_signed_up = 0 THEN 0
                ELSE ROUND(ls.total_signed_up::NUMERIC / lt.total_signed_up, 4) END as pct_signed_up,
            ls.updated_at
        FROM range_stats rs
        JOIN latest_snapshot ls ON rs.provider_code = ls.provider_code
        CROSS JOIN latest_total lt
        WHERE rs.provider_code IS NOT NULL OR p_row_type = 'ALL';
END;
$$ LANGUAGE plpgsql STABLE;