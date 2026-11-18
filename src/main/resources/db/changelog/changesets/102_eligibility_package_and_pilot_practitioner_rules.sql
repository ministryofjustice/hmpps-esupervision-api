--liquibase formatted sql

--changeset hmpps:102_eligibility_package_and_pilot_practitioner_rules-1 splitStatements:false

-- A rule can hold only when another data point satisfies a condition (applies_when_*); when it
-- doesn't, the rule is skipped. Lets the decision table branch, e.g. a youth sentence only counts
-- for someone on an accredited programme.
alter table offender_eligibility_rule
    add column applies_when_source varchar(32),
    add column applies_when_data_point varchar(128),
    add column applies_when_operator eligibility_operator,
    add column applies_when_comparison_value text;

alter table offender_eligibility_rule
    add constraint offender_eligibility_rule_applies_when_complete_check
        check ((applies_when_source is null) = (applies_when_data_point is null)
           and (applies_when_source is null) = (applies_when_operator is null)),
    add constraint offender_eligibility_rule_applies_when_value_check
        check (applies_when_operator is null
           or (applies_when_operator in ('EQUALS', 'IN_SET')) = (applies_when_comparison_value is not null));

--rollback alter table offender_eligibility_rule drop constraint offender_eligibility_rule_applies_when_value_check, drop constraint offender_eligibility_rule_applies_when_complete_check, drop column applies_when_comparison_value, drop column applies_when_operator, drop column applies_when_data_point, drop column applies_when_source;

--changeset hmpps:102_eligibility_package_and_pilot_practitioner_rules-2 splitStatements:false

-- OFFENDER_ELIGIBILITY only. After Tier D-G (11) the rules now follow the UI's decision:
--   Tier C            - eligible if the practitioner has had a pilot case, otherwise not
--   Tier A/B on an    - not eligible on a youth sentence or in early engagement, otherwise eligible
--   accredited programme
--   Tier A/B not on   - eligible if the practitioner has had a pilot case, otherwise not
--   a programme
-- Reordered so the order stays unique at every step.
update offender_eligibility_rule set rule_order = 17
where rule_set = 'OFFENDER_ELIGIBILITY' and code = 'ON_ACCREDITED_PROGRAMME';

-- Ineligible Tier, as for a provisional tier
update offender_eligibility_rule set delius_outcome_code = 'ESPIT'
where rule_set = 'OFFENDER_ELIGIBILITY' and code = 'IS_TIER_C';

update offender_eligibility_rule
set rule_order = 14,
    delius_outcome_code = 'ESPYS', -- Youth sentence
    applies_when_source = 'MANUAL', applies_when_data_point = 'ACCREDITED_PROGRAMME',
    applies_when_operator = 'EQUALS', applies_when_comparison_value = 'true'
where rule_set = 'OFFENDER_ELIGIBILITY' and code = 'ON_YOUTH_SENTENCE';

update offender_eligibility_rule
set rule_order = 15,
    applies_when_source = 'MANUAL', applies_when_data_point = 'ACCREDITED_PROGRAMME',
    applies_when_operator = 'EQUALS', applies_when_comparison_value = 'true'
where rule_set = 'OFFENDER_ELIGIBILITY' and code = 'IN_EARLY_ENGAGEMENT';

insert into offender_eligibility_rule
    (rule_order, code, question,
     source, data_point, operator, comparison_value, delius_outcome_code,
     applies_when_source, applies_when_data_point, applies_when_operator, applies_when_comparison_value,
     outcome_on_match, outcome_on_no_match,
     ineligibility_message, ineligibility_auto_message,
     comment)
values
    -- ESPSP: Not on a supervision package
    (2.5, 'ON_SUPERVISION_PACKAGE', 'Are they on a supervision package (SPA-SPG)?',
     'SUP-PACK', 'ON_PACKAGE', 'EQUALS', 'true', 'ESPSP',
     null, null, null, null,
     'CONTINUE', 'NOT_ELIGIBLE',
     '{{offender}} is not eligible for online check ins because they are not on a supervision package.',
     'is not on a supervision package',
     null),

    -- The pilot replaces the UI's pilot question: has the practitioner ever had a person in online
    -- check ins before the pilot cut-off (offender_v2.in_pilot)? It opens Tier C, and Tier A/B for
    -- those not on an accredited programme.
    (12.5, 'PRACTITIONER_HAS_PILOT_CASE_TIER_C', 'Has the practitioner of this Tier C person ever had a pilot case?',
     'ESUP', 'PRACTITIONER_PILOT', 'EQUALS', 'true', null,
     'TIER', 'TIER', 'EQUALS', 'C',
     'ELIGIBLE', 'CONTINUE',
     null, null,
     null),

    (16, 'PRACTITIONER_HAS_PILOT_CASE_TIER_A_B', 'Has the practitioner of this Tier A/B person, not on an accredited programme, ever had a pilot case?',
     'ESUP', 'PRACTITIONER_PILOT', 'EQUALS', 'true', null,
     null, null, null, null,
     'ELIGIBLE', 'CONTINUE',
     null, null,
     null);

--rollback delete from offender_eligibility_rule where rule_set = 'OFFENDER_ELIGIBILITY' and code in ('ON_SUPERVISION_PACKAGE', 'PRACTITIONER_HAS_PILOT_CASE_TIER_C', 'PRACTITIONER_HAS_PILOT_CASE_TIER_A_B');
--rollback update offender_eligibility_rule set rule_order = 16, applies_when_source = null, applies_when_data_point = null, applies_when_operator = null, applies_when_comparison_value = null where rule_set = 'OFFENDER_ELIGIBILITY' and code = 'IN_EARLY_ENGAGEMENT';
--rollback update offender_eligibility_rule set delius_outcome_code = null where rule_set = 'OFFENDER_ELIGIBILITY' and code = 'IS_TIER_C';
--rollback update offender_eligibility_rule set rule_order = 15, delius_outcome_code = null, applies_when_source = null, applies_when_data_point = null, applies_when_operator = null, applies_when_comparison_value = null where rule_set = 'OFFENDER_ELIGIBILITY' and code = 'ON_YOUTH_SENTENCE';
--rollback update offender_eligibility_rule set rule_order = 14 where rule_set = 'OFFENDER_ELIGIBILITY' and code = 'ON_ACCREDITED_PROGRAMME';
