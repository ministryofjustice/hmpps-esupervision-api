-- calculates average time taken by a POP to complete a checkin

with checkin_info as (
    select coalesce(t.location, 'UNKNOWN') as location,
           c.offender_id offender,
           EXTRACT(EPOCH FROM (submitted_at - checkin_started_at)) as completion_time
    from offender_checkin c
             join offender o on o.id = c.offender_id
             left join tmp_practitioner_sites t on o.practitioner = t.practitioner
    where c.status in ('SUBMITTED', 'REVIEWED')
      and c.checkin_started_at IS NOT NULL
      and (c.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound
    )
select location,
       round(avg(completion_time)) as completion_time_avg,
       count(completion_time) as completion_time_count
from checkin_info
group by location
order by location;