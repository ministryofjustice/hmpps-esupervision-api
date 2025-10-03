-- NOTE: this query expects `tmp_practitioner_sites` temporary table to exist

with checkin_info as (
    select coalesce(t.location, 'UNKNOWN') as location,
           c.uuid as checkin_uuid
    from offender_checkin c
             join offender o on o.id = c.offender_id
             left join tmp_practitioner_sites t on t.practitioner = o.practitioner
    where c.status in ('SUBMITTED', 'REVIEWED')
    and (c.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound)
select location, count(checkin_uuid) as checkin_count
from checkin_info
group by location
order by location;