WITH checkin_info AS (
    SELECT
        t.location,
        (CASE WHEN c.id_check_auto != 'MATCH' THEN 1 ELSE 0 END) +
        (CASE WHEN c.survey_response ->> 'mentalHealth' IN ('NOT_GREAT', 'STRUGGLING') THEN 1 ELSE 0 END) +
        (CASE WHEN c.survey_response ->> 'callback' IN ('YES') THEN 1 ELSE 0 END) +
        (CASE
            WHEN c.survey_response -> 'assistance' = '["NO_HELP"]'::jsonb THEN 0
            ELSE jsonb_array_length(c.survey_response -> 'assistance')
        END)
        AS flag_count
    FROM tmp_practitioner_sites t
    LEFT JOIN offender o ON t.practitioner = o.practitioner
    LEFT JOIN offender_checkin c ON o.id = c.offender_id
        AND c.status IN ('REVIEWED', 'SUBMITTED')
        AND c.survey_response @> '{"version": "2025-07-10@pilot"}'::jsonb
        AND (c.created_at AT TIME ZONE 'Europe/London')::date BETWEEN :lowerBound AND :upperBound
)
SELECT
    location,
    COALESCE(AVG(flag_count), 0) AS average_flags
FROM checkin_info
GROUP BY location
ORDER BY location;