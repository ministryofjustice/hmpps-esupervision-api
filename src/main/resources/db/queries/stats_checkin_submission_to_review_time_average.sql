-- calculates average time from check from submission to review

with checkin_info as (
    select coalesce(t.location, 'UNKNOWN') as location,
           c.offender_id offender,
           (reviewed_at - submitted_at) as review_time
    from offender_checkin c
              join offender o on o.id = c.offender_id
              left join tmp_practitioner_sites t on o.practitioner = t.practitioner
    where c.status = 'REVIEWED' 
    and (c.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound
)
select location, avg(review_time) as review_time_avg
from checkin_info
group by location
order by location;