-- calculates the number of checkins completed per nth day of the check in window, per site

with checkin_info as (
    select coalesce(t.location, 'UNKNOWN') as location,
           c.id as checkin_id,
           (EXTRACT ('day' from (c.due_date at time zone 'Europe/London')::date  - (c.submitted_at at time zone 'Europe/London'))) as delta
    from offender_checkin c
             join offender o on o.id = c.offender_id
             left join tmp_practitioner_sites t on o.practitioner = t.practitioner
    where c.status in ('SUBMITTED', 'REVIEWED')
        and (c.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound)

select location,
       count(checkin_id) as checkin_count,
       (delta + 1)::bigint as "nth_day"
from checkin_info
group by location, "nth_day"
order by location, "nth_day"

