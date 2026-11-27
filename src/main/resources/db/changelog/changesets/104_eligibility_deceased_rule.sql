--liquibase formatted sql

--changeset esupervision:104_eligibility_deceased_rule-1 splitStatements:false

insert into offender_eligibility_rule
    (rule_order, code, question,
     source, data_point, operator, comparison_value, delius_outcome_code,
     outcome_on_match, outcome_on_no_match,
     ineligibility_message, ineligibility_auto_message,
     comment)
values
    (0.5, 'IS_ALIVE', 'Is the person alive?',
     'NDELIUS', 'DECEASED_DATE', 'IS_NULL', null, 'ESPDT',
     'CONTINUE', 'NOT_ELIGIBLE',
     '{{offender}} is not eligible for online check ins because they have passed away.',
     'has passed away',
     null);

--rollback delete from offender_eligibility_rule where rule_set = 'OFFENDER_ELIGIBILITY' and source = 'NDELIUS' and code = 'IS_ALIVE';
