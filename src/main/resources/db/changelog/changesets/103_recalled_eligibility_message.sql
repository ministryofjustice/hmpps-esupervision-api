--liquibase formatted sql

--changeset hmpps:103_recalled_eligibility_message-1 splitStatements:false

-- ineligibility_message is the column the eligibility engine reads (since changeset 86);
-- message_on_no_match is deprecated but kept in step until it is dropped.
update offender_eligibility_rule
set ineligibility_message = '{{offender}} is not eligible for online check ins because they have been recalled to prison.',
    message_on_no_match   = '{{offender}} is not eligible for online check ins because they have been recalled to prison.'
where rule_set = 'OFFENDER_ELIGIBILITY' and code = 'IS_RECALLED' and source = 'SUP-PACK';

--rollback:
--rollback update offender_eligibility_rule
--rollback set ineligibility_message = '{{offender}} is not eligible for online check ins because they have been recalled.',
--rollback     message_on_no_match   = '{{offender}} is not eligible for online check ins because they have been recalled.'
--rollback where rule_set = 'OFFENDER_ELIGIBILITY' and code = 'IS_RECALLED' and source = 'SUP-PACK';
