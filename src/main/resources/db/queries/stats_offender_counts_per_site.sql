with offender_info as (
    select o.id as offender,
           coalesce(t.location, 'UNKNOWN') as location
    from offender o
    left join tmp_practitioner_sites t on t.practitioner = o.practitioner
    where o.status in ('VERIFIED', 'INACTIVE')
        and (o.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound)

select location, count(offender) as offender_count
from offender_info
group by location
order by location;