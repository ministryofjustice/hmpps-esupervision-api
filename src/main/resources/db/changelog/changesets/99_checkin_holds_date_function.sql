--liquibase formatted sql

--changeset richard.birch:99_checkin_holds_date_function-1 splitStatements:false

-- Whether check-in c takes its date, so no other check-in is created, scheduled or asked questions
-- for on it: one that was opened (whatever happened next), one whose invite couldn't be sent, or
-- one a practitioner cancelled after the offender's schedule was last set (setting it again
-- releases the date). The one copy of this rule in SQL: must match OffenderCheckin.holdsDate, which
-- CheckinHoldsDateParityIT checks.
CREATE OR REPLACE FUNCTION checkin_holds_date(c offender_checkin_v2, o offender_v2)
    RETURNS boolean
    STABLE
    LANGUAGE sql
AS
$$
SELECT c.status IN ('CREATED', 'SUBMITTED', 'REVIEWED', 'EXPIRED')
    OR c.link_status IS NOT DISTINCT FROM 'FAILED'
    OR (c.status = 'CANCELLED'
        AND c.cancelled_by IS NOT NULL
        AND c.cancelled_at IS NOT NULL
        AND (o.schedule_updated_at IS NULL OR c.cancelled_at > o.schedule_updated_at))
$$;

--rollback DROP FUNCTION checkin_holds_date(offender_checkin_v2, offender_v2);
