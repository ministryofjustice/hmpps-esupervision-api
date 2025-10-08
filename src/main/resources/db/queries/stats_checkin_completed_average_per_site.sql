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
                sum(completed) as completed,
                sum(expired) as expired
         from checkin_info
         group by location, offender
     )
select location,
       coalesce(avg(completed), 0) as completed_avg,
       coalesce(stddev(completed), 0) as completed_stddev,
       coalesce(avg(expired), 0) as expired_avg,
       coalesce(stddev(expired), 0) as expired_stddev,
       sum(completed) as completed_total,
       sum(expired) as expired_total,
       coalesce(sum(expired)/(sum(expired) + sum(completed)) * 100, 0) as "% expired"
from counts
group by location
order by location;