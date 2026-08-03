-- liquibase formatted sql

-- changeset roland.sadowski:72_add_checkin_image_retention-1
ALTER TABLE offender_checkin_v2 ADD COLUMN IF NOT EXISTS "image_deleted_at" TIMESTAMP(6) WITH TIME ZONE;

-- rollback ALTER TABLE offender_checkin_v2 DROP COLUMN IF EXISTS "image_deleted_at";

-- changeset roland.sadowski:72_add_checkin_image_retention-2 runInTransaction:false
ALTER TYPE job_type_v2 ADD VALUE 'V2_CHECKIN_IMAGE_RETENTION';

-- rollback:
-- No easy way to remove enum value in Postgres without dropping and recreating the type,
-- which is dangerous. Leaving it as is.
