with checkin_info as (
    select coalesce(t.location, 'UNKNOWN') as location,
           c.uuid as checkin_uuid,
           coalesce(c.survey_response->'device'->>'deviceType', 'UNKNOWN') as device_type
    from offender_checkin c
             join offender o on o.id = c.offender_id
             left join tmp_practitioner_sites t on t.practitioner = o.practitioner
    where c.status in ('SUBMITTED', 'REVIEWED')
      and (c.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound)
,
counts as (
    select location, device_type, count(checkin_uuid) as checkin_count
    from checkin_info
    group by location, device_type
)
select location,
       device_type,
       checkin_count,
       sum(checkin_count) over (partition by device_type) as device_total,
       round(
            100.0 * sum(checkin_count) over (partition by device_type) / nullif(sum(checkin_count) over (), 0),
            2
       ) as percentage
from counts
order by location, device_type;
