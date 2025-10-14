with offender_info as (
  select
    coalesce(t.location, 'UNKNOWN') as location,
    l.id as event_id 
  from offender o
  left join tmp_practitioner_sites t on t.practitioner = o.practitioner
  left join offender_event_log l on o.id = l.offender_id
    and l.log_entry_type = 'OFFENDER_DEACTIVATED'
    and (l.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound
)
select
  location,
  count(event_id) as offender_count
from offender_info
group by location
order by location;