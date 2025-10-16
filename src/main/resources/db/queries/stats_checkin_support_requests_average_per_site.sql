WITH checkin_support_info AS (
    SELECT
        t.location,
        c.id as checkin_id,
        (CASE WHEN c.survey_response ->> 'callback' IN ('YES') THEN 1 ELSE 0 END) +
        (CASE
            WHEN c.survey_response -> 'assistance' = '["NO_HELP"]'::jsonb THEN 0
            ELSE jsonb_array_length(c.survey_response -> 'assistance')
        END)
        AS support_request_count
    FROM tmp_practitioner_sites t
    LEFT JOIN offender o ON t.practitioner = o.practitioner
    LEFT JOIN offender_checkin c ON o.id = c.offender_id
        AND c.status IN ('REVIEWED', 'SUBMITTED')
        AND c.survey_response IS NOT NULL
        AND (c.created_at AT TIME ZONE 'Europe/London')::date BETWEEN :lowerBound AND :upperBound
)
SELECT
    location,
    COALESCE(AVG(support_request_count), 0) AS average_support_requests
FROM checkin_support_info
GROUP BY location
ORDER BY location;