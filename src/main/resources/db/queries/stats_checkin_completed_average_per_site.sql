-- calculates average completed checkins per offender per site

with checkin_info as (
    select coalesce(t.location, 'UNKNOWN') as location,
           c.offender_id offender,
           CASE WHEN c.status IN ('SUBMITTED', 'REVIEWED') THEN 1 END as completed,
           CASE WHEN c.status = 'EXPIRED' THEN 1 END as expired
    from offender_checkin c
             join offender o on o.id = c.offender_id
             left join tmp_practitioner_sites t on o.practitioner = t.practitioner
    where c.status in ('SUBMITTED', 'REVIEWED', 'EXPIRED')
      and (c.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound),
 counts as (
         select location,
                offender,
                count(completed) as completed,
                count(expired) as expired
         from checkin_info
         group by location, offender
     )
select location,
       coalesce(avg(completed), 0) as avg_completed,
       coalesce(stddev(completed), 0) as stddev_completed,
       coalesce(avg(expired), 0) as avg_expired,
       coalesce(stddev(expired), 0) as stddev_expired,
       count(offender) as offender_count
from counts
group by location;