--liquibase formatted sql

--changeset hmpps:77_add_eligibility_rules-1 splitStatements:false

ALTER TABLE offender_v2 ADD COLUMN in_pilot bool not null default false;

UPDATE offender_v2 SET in_pilot = TRUE
WHERE created_at <= '2026-10-01';

--rollback ALTER TABLE offender_v2 DROP COLUMN in_pilot;

--changeset hmpps:77_add_eligibility_rules-2 splitStatements:false

ALTER TYPE eligibility_operator ADD VALUE IF NOT EXISTS 'IN_SET';

--changeset hmpps:77_add_eligibility_rules-3 splitStatements:false

alter table offender_eligibility_rule
    drop constraint offender_eligibility_rule_equals_null_check;

alter table offender_eligibility_rule
    add constraint offender_eligibility_rule_equals_null_check
        check ((operator in ('EQUALS', 'IN_SET')) = (comparison_value is not null));

insert into offender_eligibility_rule
(rule_order, code, question, source, data_point, operator, comparison_value,
 outcome_on_match, message_on_match, outcome_on_no_match, message_on_no_match, comment)
values

    -- NOTE: no match message is never shown
    (2, 'IS_RECALLED', 'Are they recalled on any sentence?',
     'SUP-PACK', 'RECALLED',
     'EQUALS','false',
     'CONTINUE', null,
     'NOT_ELIGIBLE', '{{offender}} has been recalled to prison.',
     null),

    (7, 'IS_TIER_PROVISIONAL', 'Is their tier provisional?',
     'TIER', 'PROVISIONAL',
     'EQUALS','false',
     'CONTINUE', null,
     'NOT_ELIGIBLE', '{{offender}} is not eligible for online check ins because they are in a provisional',
     null),

    (8, 'IN_FINAL_THIRD', 'Are they in their final third?',
     'SUP-PACK', 'FINAL_THIRD',
     'EQUALS','false',
     'CONTINUE', null,
     'NOT_ELIGIBLE', '{{offender}} is not eligible for online check ins because they are in their final third of their sentence.',
     null),

    (9, 'IS_PRACTITIONER_ASSIGNED', 'Do they have a practitioner assigned to them?',
     'NDELIUS', 'PRACTITIONER_ASSIGNED',
     'EQUALS','true',
     'CONTINUE', null,
     'NOT_ELIGIBLE', '{{offender}} must have probation practitioner assigned to them before they can be set up to use online check ins',
     null),

    (11, 'IS_TIER_D_TO_G', 'Are they in D-G?',
     'TIER', 'TIER',
     'IN_SET','D E F G',
     'ELIGIBLE', '{{offender}} are in Tiers D-G.',
     'CONTINUE', null,
     null),

    (13, 'IS_TIER_C', 'Are they in Tier C?',
     'TIER', 'TIER',
     'EQUALS','C',
     'NOT_ELIGIBLE', '{{offender}} is not eligible for online check ins because they are in Tier C.',
     'CONTINUE', null,
     null),

    (16, 'IN_EARLY_ENGAGEMENT', 'Are they in early engagement?',
     'SUP-PACK', 'EARLY_ENGAGEMENT',
     'EQUALS','false',
     'ELIGIBLE', 'They are eligible for online check ins.',
     'NOT_ELIGIBLE', '{{offender}} is not eligible for online check ins because they are in Tier A or B, on an accredited programme, but are in early engagement.',
     null)
;
