--liquibase formatted sql

--changeset madeleine.williams:80_add_additional_feedback_questions-1 splitStatements:false
-- Same as changeset 30, plus the helpsFeelSupported and lessContactNeeded questions (same pattern as gettingSupport).
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
),

helps_feel_supported_answered AS (
    SELECT
      version,
      COUNT(*)::BIGINT AS answered_count
    FROM feedback_in_month
    WHERE feedback ? 'helpsFeelSupported'
      AND NULLIF(feedback->>'helpsFeelSupported', '') IS NOT NULL
    GROUP BY version
),

helps_feel_supported_counts_answered AS (
    SELECT
        version,
        jsonb_object_agg(answer, count) AS counts_answered
    FROM (
        SELECT
            version,
            feedback->>'helpsFeelSupported' AS answer,
            COUNT(*)::BIGINT AS count
        FROM feedback_in_month
        WHERE feedback ? 'helpsFeelSupported'
          AND NULLIF(feedback->>'helpsFeelSupported', '') IS NOT NULL
        GROUP BY version, answer
    ) x
    GROUP BY version
),

helps_feel_supported_counts AS (
    SELECT
      t.version,
      COALESCE(h.counts_answered, '{}'::jsonb)
      || jsonb_build_object('notAnswered', (t.total_feedback - COALESCE(a.answered_count, 0))::BIGINT)
      AS counts
    FROM totals t
    LEFT JOIN helps_feel_supported_counts_answered h ON h.version = t.version
    LEFT JOIN helps_feel_supported_answered a ON a.version = t.version
),

helps_feel_supported_pct AS (
  SELECT
    hfc.version,
    COALESCE(p.pct, '{}'::jsonb) AS pct
  FROM helps_feel_supported_counts hfc
  JOIN totals t ON t.version = hfc.version
  LEFT JOIN LATERAL (
    SELECT jsonb_object_agg(
      e.key,
      ROUND(
        (e.value)::BIGINT::NUMERIC
        / GREATEST(
            (t.total_feedback - COALESCE((hfc.counts->>'notAnswered')::BIGINT, 0)),
            1
          )::NUMERIC,
        4
      )
    ) AS pct
    FROM jsonb_each(hfc.counts) AS e(key, value)
    WHERE e.key <> 'notAnswered'
  ) p ON TRUE
),

less_contact_needed_answered AS (
    SELECT
      version,
      COUNT(*)::BIGINT AS answered_count
    FROM feedback_in_month
    WHERE feedback ? 'lessContactNeeded'
      AND NULLIF(feedback->>'lessContactNeeded', '') IS NOT NULL
    GROUP BY version
),

less_contact_needed_counts_answered AS (
    SELECT
        version,
        jsonb_object_agg(answer, count) AS counts_answered
    FROM (
        SELECT
            version,
            feedback->>'lessContactNeeded' AS answer,
            COUNT(*)::BIGINT AS count
        FROM feedback_in_month
        WHERE feedback ? 'lessContactNeeded'
          AND NULLIF(feedback->>'lessContactNeeded', '') IS NOT NULL
        GROUP BY version, answer
    ) x
    GROUP BY version
),

less_contact_needed_counts AS (
    SELECT
      t.version,
      COALESCE(l.counts_answered, '{}'::jsonb)
      || jsonb_build_object('notAnswered', (t.total_feedback - COALESCE(a.answered_count, 0))::BIGINT)
      AS counts
    FROM totals t
    LEFT JOIN less_contact_needed_counts_answered l ON l.version = t.version
    LEFT JOIN less_contact_needed_answered a ON a.version = t.version
),

less_contact_needed_pct AS (
  SELECT
    lcc.version,
    COALESCE(p.pct, '{}'::jsonb) AS pct
  FROM less_contact_needed_counts lcc
  JOIN totals t ON t.version = lcc.version
  LEFT JOIN LATERAL (
    SELECT jsonb_object_agg(
      e.key,
      ROUND(
        (e.value)::BIGINT::NUMERIC
        / GREATEST(
            (t.total_feedback - COALESCE((lcc.counts->>'notAnswered')::BIGINT, 0)),
            1
          )::NUMERIC,
        4
      )
    ) AS pct
    FROM jsonb_each(lcc.counts) AS e(key, value)
    WHERE e.key <> 'notAnswered'
  ) p ON TRUE
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

UNION ALL

SELECT p_month, t.version, 'helpsFeelSupported', t.total_feedback, hfc.counts, hfp.pct, now()
FROM totals t
JOIN helps_feel_supported_counts hfc ON hfc.version = t.version
JOIN helps_feel_supported_pct hfp ON hfp.version = t.version

UNION ALL

SELECT p_month, t.version, 'lessContactNeeded', t.total_feedback, lcc.counts, lcp.pct, now()
FROM totals t
JOIN less_contact_needed_counts lcc ON lcc.version = t.version
JOIN less_contact_needed_pct lcp ON lcp.version = t.version

ON CONFLICT (month, feedback_version, feedback_key)
DO UPDATE SET
    total = EXCLUDED.total,
    counts = EXCLUDED.counts,
    pct = EXCLUDED.pct,
    updated_at = now();

$$;

--changeset madeleine.williams:80_add_additional_feedback_questions-2 splitStatements:false
DROP MATERIALIZED VIEW IF EXISTS total_feedback_monthly;

CREATE MATERIALIZED VIEW total_feedback_monthly AS
WITH
feedback_version_overall AS (
  SELECT COALESCE(MAX((f.feedback->>'version')::int), 1) AS feedback_version
  FROM feedback f
),

month_totals AS (
  SELECT
    date_trunc('month', f.created_at)::date AS month,
    COUNT(*)::bigint AS feedback_total
  FROM feedback f
  JOIN feedback_version_overall fvo
    ON COALESCE((f.feedback->>'version')::int, 1) = fvo.feedback_version
  GROUP BY 1
),

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

counts_by_month_key AS (
  SELECT
    month,
    feedback_key,
    COALESCE(jsonb_object_agg(option_key, option_count), '{}'::jsonb) AS counts
  FROM counts_expanded
  GROUP BY month, feedback_key
),

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
    COALESCE(MAX(pbmk.pct::text)    FILTER (WHERE pbmk.feedback_key = 'improvements'), '{}')::jsonb AS improvements_pct,

    COALESCE(MAX(cbmk.counts::text) FILTER (WHERE cbmk.feedback_key = 'helpsFeelSupported'), '{}')::jsonb AS helps_feel_supported_counts,
    COALESCE(MAX(pbmk.pct::text)    FILTER (WHERE pbmk.feedback_key = 'helpsFeelSupported'), '{}')::jsonb AS helps_feel_supported_pct,

    COALESCE(MAX(cbmk.counts::text) FILTER (WHERE cbmk.feedback_key = 'lessContactNeeded'), '{}')::jsonb AS less_contact_needed_counts,
    COALESCE(MAX(pbmk.pct::text)    FILTER (WHERE pbmk.feedback_key = 'lessContactNeeded'), '{}')::jsonb AS less_contact_needed_pct

FROM month_totals mt
LEFT JOIN counts_by_month_key cbmk ON cbmk.month = mt.month
LEFT JOIN pct_by_month_key pbmk    ON pbmk.month = mt.month AND pbmk.feedback_key = cbmk.feedback_key
GROUP BY mt.month, mt.feedback_total
ORDER BY mt.month;

CREATE UNIQUE INDEX total_feedback_monthly_month_ux
  ON total_feedback_monthly(month);

--changeset madeleine.williams:80_add_additional_feedback_questions-3 splitStatements:false
DROP FUNCTION IF EXISTS get_total_feedback_summary(DATE, DATE);

CREATE OR REPLACE FUNCTION get_total_feedback_summary(from_month DATE, to_month DATE)
    -- from_month (inclusive) and to_month (exclusive)
RETURNS TABLE (
                  feedback_total BIGINT,
                  how_easy_counts JSONB,
                  how_easy_pct JSONB,
                  getting_support_counts JSONB,
                  getting_support_pct JSONB,
                  improvements_counts JSONB,
                  improvements_pct JSONB,
                  helps_feel_supported_counts JSONB,
                  helps_feel_supported_pct JSONB,
                  less_contact_needed_counts JSONB,
                  less_contact_needed_pct JSONB
              ) AS $$
BEGIN
    RETURN QUERY
    WITH combined_counts AS (
        SELECT
            COALESCE(SUM(total_feedback_monthly.feedback_total), 0)::BIGINT as total,
            (SELECT jsonb_object_agg(key, val) FROM (
                SELECT key, SUM((value)::bigint) val FROM total_feedback_monthly t, jsonb_each(t.how_easy_counts)
                WHERE month >= from_month AND month < to_month
                GROUP BY key) s
            ) as easy_counts,
            (SELECT jsonb_object_agg(key, val) FROM (
                SELECT key, SUM((value)::bigint) val FROM total_feedback_monthly t, jsonb_each(t.getting_support_counts)
                WHERE month >= from_month AND month < to_month GROUP BY key) s
            ) as support_counts,
            (SELECT jsonb_object_agg(key, val) FROM (
                SELECT key, SUM((value)::bigint) val FROM total_feedback_monthly t, jsonb_each(t.improvements_counts)
                WHERE month >= from_month AND month < to_month GROUP BY key) s
            ) as imp_counts,
            (SELECT jsonb_object_agg(key, val) FROM (
                SELECT key, SUM((value)::bigint) val FROM total_feedback_monthly t, jsonb_each(t.helps_feel_supported_counts)
                WHERE month >= from_month AND month < to_month GROUP BY key) s
            ) as help_counts,
            (SELECT jsonb_object_agg(key, val) FROM (
                SELECT key, SUM((value)::bigint) val FROM total_feedback_monthly t, jsonb_each(t.less_contact_needed_counts)
                WHERE month >= from_month AND month < to_month GROUP BY key) s
            ) as contact_counts
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
                 COALESCE((c.imp_counts->>'notAnswered')::bigint, 0) AS imp_na,

                 COALESCE(c.help_counts, '{}'::jsonb) as help_counts,
                 COALESCE((SELECT SUM(v::bigint) FROM jsonb_each_text(c.help_counts) t(k,v)), 0) AS help_total,
                 COALESCE((c.help_counts->>'notAnswered')::bigint, 0) AS help_na,

                 COALESCE(c.contact_counts, '{}'::jsonb) as contact_counts,
                 COALESCE((SELECT SUM(v::bigint) FROM jsonb_each_text(c.contact_counts) t(k,v)), 0) AS contact_total,
                 COALESCE((c.contact_counts->>'notAnswered')::bigint, 0) AS contact_na
             FROM combined_counts c
         )
    SELECT
        calc.feedback_total,
        calc.easy_counts,
        feedback_calc_pct(calc.easy_counts, calc.easy_total::BIGINT, calc.easy_na::BIGINT),
        calc.support_counts,
        feedback_calc_pct(calc.support_counts, calc.support_total::BIGINT, calc.support_na::BIGINT),
        calc.imp_counts,
        feedback_calc_pct(calc.imp_counts, calc.imp_total::BIGINT, calc.imp_na::BIGINT),
        calc.help_counts,
        feedback_calc_pct(calc.help_counts, calc.help_total::BIGINT, calc.help_na::BIGINT),
        calc.contact_counts,
        feedback_calc_pct(calc.contact_counts, calc.contact_total::BIGINT, calc.contact_na::BIGINT)
    FROM calculations calc;
END;
$$ LANGUAGE plpgsql STABLE;
