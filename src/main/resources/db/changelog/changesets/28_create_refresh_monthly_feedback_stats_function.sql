--liquibase formatted sql

--changeset rob.catton:28_create_refresh_monthly_feedback_stats_function splitStatements:false
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

how_easy_pct AS (
    SELECT
      t.version,
      COALESCE(
        jsonb_object_agg(
          c.key,
          ROUND(c.value::NUMERIC / GREATEST(COALESCE(a.answered_count, 0), 1)::NUMERIC, 4)
        ),
        '{}'::jsonb
      ) AS pct
    FROM totals t
    LEFT JOIN how_easy_counts_answered h ON h.version = t.version
    LEFT JOIN how_easy_answered a ON a.version = t.version
    CROSS JOIN LATERAL jsonb_each(COALESCE(h.counts_answered, '{}'::jsonb)) AS c(key, value)
    GROUP BY t.version, a.answered_count
),

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

getting_support_pct AS (
    SELECT
      t.version,
      COALESCE(
        jsonb_object_agg(
          c.key,
          ROUND(c.value::NUMERIC / GREATEST(COALESCE(a.answered_count, 0), 1)::NUMERIC, 4)
        ),
        '{}'::jsonb
      ) AS pct
    FROM totals t
    LEFT JOIN getting_support_counts_answered g ON g.version = t.version
    LEFT JOIN getting_support_answered a ON a.version = t.version
    CROSS JOIN LATERAL jsonb_each(COALESCE(g.counts_answered, '{}'::jsonb)) AS c(key, value)
    GROUP BY t.version, a.answered_count
),

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

improvements_pct AS (
    SELECT
      t.version,
      COALESCE(
        jsonb_object_agg(
          c.key,
          ROUND(c.value::NUMERIC / GREATEST(COALESCE(a.answered_count, 0), 1)::NUMERIC, 4)
        ),
        '{}'::jsonb
      ) AS pct
    FROM totals t
    LEFT JOIN improvements_counts_answered i ON i.version = t.version
    LEFT JOIN improvements_answered a ON a.version = t.version
    CROSS JOIN LATERAL jsonb_each(COALESCE(i.counts_answered, '{}'::jsonb)) AS c(key, value)
    GROUP BY t.version, a.answered_count
)

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
