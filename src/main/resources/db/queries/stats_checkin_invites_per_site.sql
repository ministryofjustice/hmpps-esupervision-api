-- NOTE: this query expects `tmp_practitioner_sites` temporary table to exist

with checkin_info as (
    select coalesce(t.location, 'UNKNOWN') as location,
           1 as notification
    from offender_checkin c
             join offender o on o.id = c.offender_id
             join offender_checkin_notification cn on cn.checkin = c.uuid
             left join tmp_practitioner_sites t on o.practitioner = t.practitioner
    where (c.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound
)
select location, count(notification) as "count" from checkin_info
group by location
order by location