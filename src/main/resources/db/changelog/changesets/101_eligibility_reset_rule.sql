--liquibase formatted sql

--changeset roland.sadowski:101_eligibility_reset_rule-1 splitStatements:false

insert into offender_eligibility_rule
    (rule_order, code, question,
     source, data_point, operator, comparison_value, delius_outcome_code,
     outcome_on_match, outcome_on_no_match,
     ineligibility_message, ineligibility_auto_message,
     comment)
values
    (2, 'IS_CONTACT_SUSPENDED', 'Are they in reset?',
     'NDELIUS', 'CONTACT_SUSPENDED','EQUALS','false', 'ESPRS',
     'CONTINUE', 'NOT_ELIGIBLE',
     '{{offender}} is not eligible for online check ins because they are suspended (in reset) in NDelius.',
     'is suspended (in reset) in NDelius',
     null);

--rollback delete from offender_eligibility_rule where rule_set = 'OFFENDER_ELIGIBILITY' and source = 'NDELIUS' and code = 'IS_CONTACT_SUSPENDED';
