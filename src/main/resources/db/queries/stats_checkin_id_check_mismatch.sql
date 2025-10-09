-- calculates stats on automated ID check (rekognition) accuracy

with checkin_info as (
    select coalesce(t.location, 'UNKNOWN') as location,
           CASE WHEN c.id_check_auto != c.id_check_manual THEN 1 END as mismatch,
           CASE WHEN c.id_check_auto = 'MATCH' and c.id_check_manual = 'NO_MATCH' THEN 1 ELSE 0 END as false_positive,
           CASE WHEN c.id_check_auto = 'NO_MATCH' and c.id_check_manual = 'MATCH' THEN 1 ELSE 0 END as false_negative
    from offender_checkin c
             join offender o on o.id = c.offender_id
             left join tmp_practitioner_sites t on o.practitioner = t.practitioner
    where c.status in ('REVIEWED')
      --and c.id_check_manual != c.id_check_auto
      and (c.created_at at time zone 'Europe/London')::date between :lowerBound and :upperBound)
select location,
       sum(mismatch) as mismatch_count,
       avg(false_positive) as false_positive_avg,
       stddev(false_positive) as false_positive_stddev,
       avg(false_negative) as false_negative_avg,
       stddev(false_negative) as false_negative_stddev
from checkin_info
group by location
order by location;