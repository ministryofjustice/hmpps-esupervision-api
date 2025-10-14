WITH checkin_info AS (
    SELECT coalesce(t.location, 'UNKNOWN') as location,
           CASE WHEN c.id_check_auto != 'MATCH' THEN 1
                WHEN c.survey_response ->> 'mentalHealth' IN ('NOT_GREAT', 'STRUGGLING') THEN 1
                WHEN c.survey_response ->> 'callback' IN ('YES') THEN 1
                WHEN c.survey_response -> 'assistance' != '["NO_HELP"]'::jsonb THEN 1
                ELSE 0
           END as has_flags
    FROM offender_checkin c
        JOIN offender o on o.id = c.offender_id
        LEFT JOIN tmp_practitioner_sites t on o.practitioner = t.practitioner
    WHERE c.status in ('REVIEWED', 'SUBMITTED')
      AND survey_response @> '{"version": "2025-07-10@pilot"}'::jsonb
      AND (c.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound)
SELECT location,
       sum(has_flags)
FROM checkin_info
GROUP BY location
ORDER BY location