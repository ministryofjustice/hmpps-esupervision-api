WITH checkin_info AS (
    SELECT
        COALESCE(t.location, 'UNKNOWN') as location,
        -- Calculate total flags for this check-in
        (CASE WHEN c.id_check_auto != 'MATCH' THEN 1 ELSE 0 END) +
        (CASE WHEN c.survey_response ->> 'mentalHealth' IN ('NOT_GREAT', 'STRUGGLING') THEN 1 ELSE 0 END) +
        (CASE WHEN c.survey_response ->> 'callback' IN ('YES') THEN 1 ELSE 0 END) +
        (CASE
            WHEN c.survey_response -> 'assistance' = '["NO_HELP"]'::jsonb THEN 0
            ELSE jsonb_array_length(c.survey_response -> 'assistance')
        END) AS flag_count,
        -- Calculate call back request
        (CASE WHEN c.survey_response ->> 'callback' IN ('YES') THEN 1 ELSE 0 END) AS has_callback_request

    FROM offender_checkin c
    JOIN offender o ON c.offender_id = o.id
    LEFT JOIN tmp_practitioner_sites t ON t.practitioner = o.practitioner
    WHERE c.status IN ('REVIEWED', 'SUBMITTED')
      AND c.survey_response @> '{"version": "2025-07-10@pilot"}'::jsonb
      AND (c.created_at AT TIME ZONE 'Europe/London')::date BETWEEN :lowerBound AND :upperBound
),
all_sites AS (
    SELECT DISTINCT location FROM tmp_practitioner_sites
    UNION
    SELECT 'UNKNOWN'
)
SELECT
    s.location,
    COALESCE(AVG(cc.flag_count), 0) AS average_flags,
    COALESCE(AVG(cc.has_callback_request) * 100, 0) AS callback_request_percentage,
    COUNT(cc.flag_count) AS checkin_count

FROM all_sites s
LEFT JOIN checkin_info cc ON s.location = cc.location
GROUP BY s.location
ORDER BY s.location;