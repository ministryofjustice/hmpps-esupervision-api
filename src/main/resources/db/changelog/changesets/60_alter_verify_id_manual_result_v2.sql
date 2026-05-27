-- liquibase formatted sql

-- changeset dave.iles:60_alter_verify_id_manual_result_v2.sql-1 splitStatements:false
ALTER TYPE public.verify_id_manual_result_v2
    ADD VALUE 'MATCH_WITH_CONCERN' AFTER 'NO_MATCH';

--rollback:
-- No easy way to remove enum value in Postgres without dropping and recreating the type,
-- which is dangerous. Leaving it as is.