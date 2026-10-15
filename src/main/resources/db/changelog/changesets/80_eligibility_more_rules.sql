--liquibase formatted sql

--changeset hmpps:80_eligibility_more_rules-1 splitStatements:false

insert into offender_eligibility_rule
(rule_order, code, question, source, data_point, operator, comparison_value,
 outcome_on_match, message_on_match, outcome_on_no_match, message_on_no_match, comment)
values

    -- eventual source: NDELIUS
    (10, 'HAS_DEVICE_RESTRICTION', 'Do they have sentence conditions that mean they can''t use a device or the internet?',
     'MANUAL', 'DEVICE_RESTRICTION',
     'EQUALS','false',
     'CONTINUE', null,
     'NOT_ELIGIBLE', '{{offender}} is not eligible for online check ins because they have sentence conditions that mean they can''t use a device or internet.',
     null),

    -- eventual source: NDELIUS
    (10.5, 'TIER_MISSING', 'Do they have a Tier score?',
     'TIER', 'TIER',
     'EQUALS','MISSING',
     'NOT_ELIGIBLE', '{{offender}} is not eligible for online check ins because they have not been assigned a tier yet.',
     'CONTINUE', null,
     null),

    (12, 'IS_PILOT_USER', 'Are they a pilot user?',
     'ESUP', 'PILOT_USER',
     'EQUALS','false',
     'CONTINUE', null,
     'ELIGIBLE', '{{offender}} is eligible',
     null),

    -- NOTE: no ineligibility message in figma
    -- eventual source: NDELIUS
    (14, 'ON_ACCREDITED_PROGRAMME', 'Are they on accredited programme?',
     'MANUAL', 'ACCREDITED_PROGRAMME',
     'EQUALS','true',
     'CONTINUE', null,
     'NOT_ELIGIBLE', '{{offender}} is not eligible for online check ins because they are in Tier A or B, and are not on accredited programme.',
     null),

    -- eventual source: NDELIUS
    (15, 'ON_YOUTH_SENTENCE', 'Are they on a youth sentence?',
     'MANUAL', 'YOUTH_SENTENCE',
     'EQUALS','false',
     'CONTINUE', null,
     'NOT_ELIGIBLE', '{{offender}} is not eligible for online check ins because they are in Tier A or B, on an accredited programme, but are on a youth sentence.',
     null)
;
-- no rollback for this one

--changeset hmpps:80_eligibility_more_rules-2 splitStatements:false

-- update the message and position of existing rule
update offender_eligibility_rule
set
    rule_order = 3,
    message_on_no_match = '{{offender}} is not eligible for online check ins because they have been recalled.'
where rule_set = 'OFFENDER_ELIGIBILITY' and code = 'IS_RECALLED' and source = 'SUP-PACK';

--rollback update offender_eligibility_rule set rule_order = 2 where code = 'IS_RECALLED' and source = 'SUP-PACK';
