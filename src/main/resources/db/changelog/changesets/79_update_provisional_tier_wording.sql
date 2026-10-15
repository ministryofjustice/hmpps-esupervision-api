--liquibase formatted sql

--changeset hmpps:79_update_provisional_tier_wording-1 splitStatements:false

UPDATE offender_eligibility_rule
SET message_on_no_match = '{{offender}} is not eligible for online check ins because they are in a provisional tier.'
WHERE code = 'IS_TIER_PROVISIONAL';

--rollback UPDATE offender_eligibility_rule SET message_on_no_match = '{{offender}} is not eligible for online check ins because they are in a provisional' WHERE code = 'IS_TIER_PROVISIONAL';
