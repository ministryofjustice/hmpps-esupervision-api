--liquibase formatted sql

--changeset roland.sadowski:43_add_custom_questions_reminder_job_enum splitStatements:false
ALTER TYPE job_type_v2 ADD VALUE 'V2_PRACTITIONER_CUSTOM_QUESTIONS_REMINDER';

--rollback:
-- No easy way to remove enum value in Postgres without dropping and recreating the type, 
-- which is dangerous. Leaving it as is.
