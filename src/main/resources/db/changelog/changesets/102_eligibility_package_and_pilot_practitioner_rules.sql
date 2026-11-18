--liquibase formatted sql

--changeset hmpps:102_eligibility_package_and_pilot_practitioner_rules-1 splitStatements:false

-- Only OFFENDER_ELIGIBILITY. Note POST /v2/offenders/crn/{crn}/eligibility reads it by default
-- (app.offender-eligibility.check-rule-set); setup, reactivation and the sync job do not unless configured.
insert into offender_eligibility_rule
    (rule_order, code, question,
     source, data_point, operator, comparison_value, delius_outcome_code,
     outcome_on_match, outcome_on_no_match,
     ineligibility_message, ineligibility_auto_message,
     comment)
values
    -- TODO: NDelius outcome code for "not on a supervision package" still to be agreed
    (2.5, 'ON_SUPERVISION_PACKAGE', 'Are they on a supervision package (SPA-SPG)?',
     'SUP-PACK', 'ON_PACKAGE', 'EQUALS', 'true', null,
     'CONTINUE', 'NOT_ELIGIBLE',
     '{{offender}} is not eligible for online check ins because they are not on a supervision package.',
     'is not on a supervision package',
     null),

    -- Replaces the UI's pilot question: has the practitioner ever had a person in online check ins
    -- before the pilot cut-off (offender_v2.in_pilot)?
    (12.5, 'PRACTITIONER_HAS_PILOT_CASE', 'Has their practitioner ever had a pilot case?',
     'ESUP', 'PRACTITIONER_PILOT', 'EQUALS', 'false', null,
     'CONTINUE', 'ELIGIBLE',
     null,
     null,
     null);

--rollback delete from offender_eligibility_rule where rule_set = 'OFFENDER_ELIGIBILITY' and code in ('ON_SUPERVISION_PACKAGE', 'PRACTITIONER_HAS_PILOT_CASE');
