--liquibase formatted sql

--changeset hmpps:78_allow_null_first_checkin_for_ad_hoc-1 splitStatements:false
ALTER TABLE offender_v2
  ALTER COLUMN first_checkin DROP NOT NULL;

ALTER TABLE offender_v2
  ADD CONSTRAINT offender_v2_first_checkin_check
  CHECK (checkin_mode = 'AD_HOC' OR first_checkin IS NOT NULL);

--rollback ALTER TABLE offender_v2 DROP CONSTRAINT offender_v2_first_checkin_check;
--rollback ALTER TABLE offender_v2 ALTER COLUMN first_checkin SET NOT NULL;
