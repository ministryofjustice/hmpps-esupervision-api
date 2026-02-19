--liquibase formatted sql

--changeset rob.catton:30_update_refresh_monthly_feedback_stats_fn splitStatements:false
CREATE OR REPLACE FUNCTION refresh_monthly_feedback_stats(
    p_month DATE,
    p_start TIMESTAMPTZ,
    p_end   TIMESTAMPTZ
)
RETURNS void
LANGUAGE sql
AS $$
WITH feedback_in_month AS (
    SELECT
        COALESCE((feedback->>'version')::INT, 1) AS version,
        feedback
    FROM feedback
    WHERE created_at >= p_start
      AND created_at <  p_end
),

totals AS (
    SELECT
        version,
        COUNT(*)::BIGINT AS total_feedback
    FROM feedback_in_month
    GROUP BY version
),

how_easy_answered AS (
    SELECT
      version,
      COUNT(*)::BIGINT AS answered_count
    FROM feedback_in_month
    WHERE feedback ? 'howEasy'
      AND NULLIF(feedback->>'howEasy', '') IS NOT NULL
    GROUP BY version
),

how_easy_counts_answered AS (
    SELECT
        version,
        jsonb_object_agg(answer, count) AS counts_answered
    FROM (
        SELECT
            version,
            feedback->>'howEasy' AS answer,
            COUNT(*)::BIGINT AS count
        FROM feedback_in_month
        WHERE feedback ? 'howEasy'
          AND NULLIF(feedback->>'howEasy', '') IS NOT NULL
        GROUP BY version, answer
    ) x
    GROUP BY version
),

how_easy_counts AS (
    SELECT
      t.version,
      COALESCE(h.counts_answered, '{}'::jsonb)
      || jsonb_build_object('notAnswered', (t.total_feedback - COALESCE(a.answered_count, 0))::BIGINT)
      AS counts
    FROM totals t
    LEFT JOIN how_easy_counts_answered h ON h.version = t.version
    LEFT JOIN how_easy_answered a ON a.version = t.version
),

/* ===================================================================================================
   CHANGE START - fixing CTE so they insert rows even if nobody has answered this question this month
   =================================================================================================== */

how_easy_pct AS (
  SELECT
    hec.version,
    COALESCE(p.pct, '{}'::jsonb) AS pct
  FROM how_easy_counts hec
  JOIN totals t ON t.version = hec.version
  LEFT JOIN LATERAL (
    SELECT jsonb_object_agg(
      e.key,
      ROUND(
        (e.value)::BIGINT::NUMERIC
        / GREATEST(
            (t.total_feedback - COALESCE((hec.counts->>'notAnswered')::BIGINT, 0)),
            1
          )::NUMERIC,
        4
      )
    ) AS pct
    FROM jsonb_each(hec.counts) AS e(key, value)
    WHERE e.key <> 'notAnswered'
  ) p ON TRUE
),

/* ===========================
   CHANGE END
   =========================== */

getting_support_answered AS (
    SELECT
      version,
      COUNT(*)::BIGINT AS answered_count
    FROM feedback_in_month
    WHERE feedback ? 'gettingSupport'
      AND NULLIF(feedback->>'gettingSupport', '') IS NOT NULL
    GROUP BY version
),

getting_support_counts_answered AS (
    SELECT
        version,
        jsonb_object_agg(answer, count) AS counts_answered
    FROM (
        SELECT
            version,
            feedback->>'gettingSupport' AS answer,
            COUNT(*)::BIGINT AS count
        FROM feedback_in_month
        WHERE feedback ? 'gettingSupport'
          AND NULLIF(feedback->>'gettingSupport', '') IS NOT NULL
        GROUP BY version, answer
    ) x
    GROUP BY version
),

getting_support_counts AS (
    SELECT
      t.version,
      COALESCE(g.counts_answered, '{}'::jsonb)
      || jsonb_build_object('notAnswered', (t.total_feedback - COALESCE(a.answered_count, 0))::BIGINT)
      AS counts
    FROM totals t
    LEFT JOIN getting_support_counts_answered g ON g.version = t.version
    LEFT JOIN getting_support_answered a ON a.version = t.version
),

/* ===================================================================================================
   CHANGE START - fixing CTE so they insert rows even if nobody has answered this question this month
   =================================================================================================== */

getting_support_pct AS (
  SELECT
    gsc.version,
    COALESCE(p.pct, '{}'::jsonb) AS pct
  FROM getting_support_counts gsc
  JOIN totals t ON t.version = gsc.version
  LEFT JOIN LATERAL (
    SELECT jsonb_object_agg(
      e.key,
      ROUND(
        (e.value)::BIGINT::NUMERIC
        / GREATEST(
            (t.total_feedback - COALESCE((gsc.counts->>'notAnswered')::BIGINT, 0)),
            1
          )::NUMERIC,
        4
      )
    ) AS pct
    FROM jsonb_each(gsc.counts) AS e(key, value)
    WHERE e.key <> 'notAnswered'
  ) p ON TRUE
),

/* ===========================
   CHANGE END
   =========================== */

improvements_answered AS (
    SELECT
      version,
      COUNT(*)::BIGINT AS answered_count
    FROM feedback_in_month
    WHERE feedback ? 'improvements'
      AND jsonb_typeof(feedback->'improvements') = 'array'
      AND jsonb_array_length(feedback->'improvements') > 0
    GROUP BY version
),

improvements_expanded AS (
    SELECT
        version,
        jsonb_array_elements_text(feedback->'improvements') AS improvement
    FROM feedback_in_month
    WHERE feedback ? 'improvements'
      AND jsonb_typeof(feedback->'improvements') = 'array'
),

improvements_counts_answered AS (
    SELECT
      version,
      COALESCE(jsonb_object_agg(key, value), '{}'::jsonb) AS counts_answered
    FROM (
      SELECT
        version,
        improvement AS key,
        COUNT(*)::BIGINT AS value
      FROM improvements_expanded
      GROUP BY version, improvement
    ) x
    GROUP BY version
),

improvements_counts AS (
    SELECT
      t.version,
      COALESCE(i.counts_answered, '{}'::jsonb)
      || jsonb_build_object('notAnswered', (t.total_feedback - COALESCE(a.answered_count, 0))::BIGINT)
      AS counts
    FROM totals t
    LEFT JOIN improvements_counts_answered i ON i.version = t.version
    LEFT JOIN improvements_answered a ON a.version = t.version
),

/* ===================================================================================================
   CHANGE START - fixing CTE so they insert rows even if nobody has answered this question this month
   =================================================================================================== */

improvements_pct AS (
  SELECT
    ic.version,
    COALESCE(p.pct, '{}'::jsonb) AS pct
  FROM improvements_counts ic
  JOIN totals t ON t.version = ic.version
  LEFT JOIN LATERAL (
    SELECT jsonb_object_agg(
      e.key,
      ROUND(
        (e.value)::BIGINT::NUMERIC
        / GREATEST(
            (t.total_feedback - COALESCE((ic.counts->>'notAnswered')::BIGINT, 0)),
            1
          )::NUMERIC,
        4
      )
    ) AS pct
    FROM jsonb_each(ic.counts) AS e(key, value)
    WHERE e.key <> 'notAnswered'
  ) p ON TRUE
)

/* ===========================
   CHANGE END
   =========================== */

INSERT INTO monthly_feedback_stats (
    month,
    feedback_version,
    feedback_key,
    total,
    counts,
    pct,
    updated_at
)

SELECT p_month, t.version, 'howEasy', t.total_feedback, hc.counts, hp.pct, now()
FROM totals t
JOIN how_easy_counts hc ON hc.version = t.version
JOIN how_easy_pct hp ON hp.version = t.version

UNION ALL

SELECT p_month, t.version, 'gettingSupport', t.total_feedback, gc.counts, gp.pct, now()
FROM totals t
JOIN getting_support_counts gc ON gc.version = t.version
JOIN getting_support_pct gp ON gp.version = t.version

UNION ALL

SELECT p_month, t.version, 'improvements', t.total_feedback, ic.counts, ip.pct, now()
FROM totals t
JOIN improvements_counts ic ON ic.version = t.version
JOIN improvements_pct ip ON ip.version = t.version

ON CONFLICT (month, feedback_version, feedback_key)
DO UPDATE SET
    total = EXCLUDED.total,
    counts = EXCLUDED.counts,
    pct = EXCLUDED.pct,
    updated_at = now();

$$;
