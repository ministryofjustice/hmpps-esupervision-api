-- calculates the number of checkins completed per nth day of the check in window, per site

with checkin_info as (
    select coalesce(t.location, 'UNKNOWN') as location,
           c.id as checkin_id,
           EXTRACT ('day' from ((c.submitted_at at time zone 'Europe/London') - (c.due_date at time zone 'Europe/London'))) as delta
    from offender_checkin c
             join offender o on o.id = c.offender_id
             left join tmp_practitioner_sites t on o.practitioner = t.practitioner
    where c.status in ('SUBMITTED', 'REVIEWED')
      and (c.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound
), counts as (
    select
        location,
        (delta + 1)::bigint as nth_day,
        count(checkin_id) as checkin_count
    from checkin_info
    where delta is not null
    group by location, nth_day
)
select
    l.location,
    coalesce(c.checkin_count, 0) as checkin_count,
    s.nth_day as "nth_day"
from (select distinct location from counts) as l
cross join generate_series(1, 3) as s(nth_day)
left join counts c on l.location = c.location and s.nth_day = c.nth_day
order by c.location, s.nth_day;
