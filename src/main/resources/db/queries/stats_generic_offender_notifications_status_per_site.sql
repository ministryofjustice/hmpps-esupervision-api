with checkin_info as (
    select coalesce(t.location, 'UNKNOWN') as location,
           gn.message_type as "type",
           coalesce(gn.status, 'unknown') as status
    from generic_notification gn
             join offender o on o.id = gn.offender_id
             left join tmp_practitioner_sites t on o.practitioner = t.practitioner
    where (gn.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound
          and gn.offender_id is not null
)
select location, type, status, count(status) as "count" from checkin_info
group by location, type, status
order by location, type, status;
