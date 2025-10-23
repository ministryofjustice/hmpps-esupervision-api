with offender_info as (
    select o.id as offender,
           o.checkin_interval as checkin_interval,
           coalesce(t.location, 'UNKNOWN') as location
    from offender o
             left join tmp_practitioner_sites t on t.practitioner = o.practitioner
    where o.status not in ('INITIAL')
      and (o.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound)

select location,
       extract('days' from checkin_interval)::bigint as "frequency",
       count(checkin_interval) as "count"
from offender_info
group by location, checkin_interval
order by location;