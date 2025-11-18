with checkin_info as (
    select coalesce(t.location, 'UNKNOWN') as location
    from offender_event_log log
             join tmp_practitioner_sites t on log.practitioner = t.practitioner
    where (log.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound
      and log.offender_id is not null
      and log.log_entry_type = 'OFFENDER_CHECKIN_OUTSIDE_ACCESS'
)
select location, count(location) as "count" from checkin_info
group by location
order by location;