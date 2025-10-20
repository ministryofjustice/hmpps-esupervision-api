WITH checkin_info AS (
    SELECT
        COALESCE(t.location, 'UNKNOWN') as location,
        (CASE WHEN c.id_check_auto != 'MATCH' THEN 1 ELSE 0 END) +
        (CASE WHEN c.survey_response ->> 'mentalHealth' IN ('NOT_GREAT', 'STRUGGLING') THEN 1 ELSE 0 END) +
        (CASE WHEN c.survey_response ->> 'callback' IN ('YES') THEN 1 ELSE 0 END) +
        (CASE
            WHEN c.survey_response -> 'assistance' = '["NO_HELP"]'::jsonb THEN 0
            ELSE jsonb_array_length(c.survey_response -> 'assistance')
        END) AS flag_count
        FROM offender_checkin c
        JOIN offender o ON c.offender_id = o.id
        LEFT JOIN tmp_practitioner_sites t ON t.practitioner = o.practitioner
        AND c.status IN ('REVIEWED', 'SUBMITTED')
        AND c.survey_response @> '{"version": "2025-07-10@pilot"}'::jsonb
        AND (c.created_at AT TIME ZONE 'Europe/London')::date BETWEEN :lowerBound AND :upperBound
)
SELECT
    ci.location as location,
    COALESCE(AVG(ci.flag_count), 0) AS average_flags
FROM checkin_info ci
GROUP BY ci.location
ORDER BY location;