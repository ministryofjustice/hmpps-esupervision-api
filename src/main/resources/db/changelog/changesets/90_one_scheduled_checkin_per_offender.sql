--liquibase formatted sql

--changeset richard.birch:90_one_scheduled_checkin_per_offender-1 splitStatements:false runInTransaction:false

-- ESUP-2223: NDelius records only the next check-in, so an offender has at most one upcoming
-- (SCHEDULED) check-in. CONCURRENTLY so the index build doesn't block check-in writes.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_checkin_v2_one_scheduled_per_offender
    ON offender_checkin_v2 (offender_id)
    WHERE status = 'SCHEDULED';

--rollback DROP INDEX CONCURRENTLY IF EXISTS uk_checkin_v2_one_scheduled_per_offender;
