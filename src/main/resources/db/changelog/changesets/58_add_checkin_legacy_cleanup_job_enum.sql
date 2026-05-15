--liquibase formatted sql

--changeset richard.birch:58_add_checkin_legacy_cleanup_job_enum runInTransaction:false
ALTER TYPE job_type_v2 ADD VALUE 'V2_CHECKIN_LEGACY_CLEANUP';

--rollback:
-- No easy way to remove enum value in Postgres without dropping and recreating the type,
-- which is dangerous. Leaving it as is.
