/*
 * calculates the average time in seconds from when a practitioner
 * starts the registration for a PoP (started_at) to when a PoP is created (created_at),
 */
WITH signup_durations AS (
    SELECT
        COALESCE(sites.location, 'UNKNOWN') as location,
        EXTRACT(EPOCH FROM (setup.created_at - setup.started_at)) as duration_seconds
        
    FROM offender_setup setup
    LEFT JOIN tmp_practitioner_sites sites ON setup.practitioner = sites.practitioner
    WHERE
        setup.started_at IS NOT NULL
        AND setup.created_at > setup.started_at
        AND (setup.created_at AT TIME ZONE 'Europe/London')::date BETWEEN :lowerBound AND :upperBound
),
all_sites AS (
    SELECT DISTINCT location FROM tmp_practitioner_sites
)
SELECT
    all_sites.location,
    COALESCE(AVG(durations.duration_seconds), 0) AS review_time_avg,
    COUNT(durations.duration_seconds) AS review_time_count

FROM all_sites
LEFT JOIN signup_durations durations ON all_sites.location = durations.location
GROUP BY all_sites.location
ORDER BY all_sites.location;