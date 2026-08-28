-- changeset roland.sadowski:75_new_job_type-1 runInTransaction:false
ALTER TYPE job_type_v2 ADD VALUE 'V2_OFFENDER_ELIGIBILITY_SYNC';

-- rollback:
-- No easy way to remove enum value in Postgres without dropping and recreating the type,
-- which is dangerous. Leaving it as is.