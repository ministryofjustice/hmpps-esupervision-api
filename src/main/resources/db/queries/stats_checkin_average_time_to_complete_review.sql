/*
 * Calculates the average time in seconds taken to complete a review.
 * (reviewed_at - review_started_at)
 */
WITH checkin_info AS (
    SELECT
        COALESCE(t.location, 'UNKNOWN') as location,
        EXTRACT(EPOCH FROM (c.reviewed_at - c.review_started_at)) as review_seconds
    FROM offender_checkin c
    JOIN offender o ON c.offender_id = o.id
    LEFT JOIN tmp_practitioner_sites t ON t.practitioner = o.practitioner
    WHERE
        c.review_started_at IS NOT NULL
        AND c.reviewed_at > c.review_started_at
        AND (c.reviewed_at AT TIME ZONE 'Europe/London')::date BETWEEN :lowerBound AND :upperBound
),
all_sites AS (
    SELECT DISTINCT location FROM tmp_practitioner_sites
)
SELECT
    s.location,
    COALESCE(AVG(cc.review_seconds), 0) AS average_seconds,
    COUNT(cc.review_seconds) AS review_count

FROM all_sites s
LEFT JOIN checkin_info cc ON s.location = cc.location
GROUP BY s.location
ORDER BY s.location;