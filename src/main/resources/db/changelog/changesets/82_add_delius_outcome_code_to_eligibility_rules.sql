--liquibase formatted sql

--changeset hmpps:82_add_delius_outcome_code_to_eligibility_rules-1

alter table offender_eligibility_rule
    add column delius_outcome_code varchar(5);

update offender_eligibility_rule
set delius_outcome_code = case code
    when 'HAS_ACTIVE_EVENT' then 'ESPNA'
    when 'IS_RECALLED' then 'ESPRC'
    when 'IS_TIER_MISSING' then 'ESPME'
    when 'IS_TIER_PROVISIONAL' then 'ESPIT'
    when 'IN_FINAL_THIRD' then 'ESPFT'
    when 'IS_PRACTITIONER_ASSIGNED' then 'ESPNP'
    when 'HAS_DEVICE_RESTRICTION' then 'ESPDI'
    when 'IS_TIER_C' then 'ESPIT'
    when 'ON_ACCREDITED_PROGRAMME' then 'ESPAP'
    when 'IN_EARLY_ENGAGEMENT' then 'ESPEE'
end
where rule_set = 'OFFENDER_ELIGIBILITY'
  and code in (
    'HAS_ACTIVE_EVENT',
    'IS_RECALLED',
    'IS_TIER_MISSING',
    'IS_TIER_PROVISIONAL',
    'IN_FINAL_THIRD',
    'IS_PRACTITIONER_ASSIGNED',
    'HAS_DEVICE_RESTRICTION',
    'IS_TIER_C',
    'ON_ACCREDITED_PROGRAMME',
    'IN_EARLY_ENGAGEMENT'
  );

update offender_eligibility_rule
set delius_outcome_code = case code
    when 'HAS_ACTIVE_EVENT' then 'ESPNA'
    when 'IS_CONTACT_SUSPENDED' then 'ESPRS'
end
where rule_set = 'PILOT_2026_08'
  and code in (
      'HAS_ACTIVE_EVENT',
      'IS_CONTACT_SUSPENDED'
);


alter table offender_eligibility_rule
    add column ineligibility_message text,
    add column ineligibility_auto_message text;

update offender_eligibility_rule
set ineligibility_message = case
    when outcome_on_no_match = 'NOT_ELIGIBLE' then message_on_no_match
    when outcome_on_match = 'NOT_ELIGIBLE' then message_on_match
end;

alter table offender_eligibility_rule
    drop constraint offender_eligibility_rule_match_message_check,
    drop constraint offender_eligibility_rule_no_match_message_check,
    drop column message_on_match,
    drop column message_on_no_match;

-- the messages should fit the wording of the email message:
update offender_eligibility_rule
set ineligibility_auto_message = case code
    when 'HAS_ACTIVE_EVENT' then 'has no active sentence'
    when 'IS_RECALLED' then 'has been recalled to prison'
    when 'IS_TIER_MISSING' then 'is missing a Tier' -- dev
    when 'IS_TIER_PROVISIONAL' then 'has a provisional Tier' -- dev
    when 'IN_FINAL_THIRD' then 'is in the final third of their probation sentence'
    when 'IS_PRACTITIONER_ASSIGNED' then 'has no practitioner assigned'
    when 'HAS_DEVICE_RESTRICTION' then 'has device restrictions'
    when 'IS_TIER_C' then 'is in Tier C'
    when 'ON_ACCREDITED_PROGRAMME' then 'is in Tier A or B and their accredited programme has ended'
    when 'IN_EARLY_ENGAGEMENT' then 'is in Tier Tier A or B, on an accredited programme, but in early engagement'
    when 'IS_CONTACT_SUSPENDED' then 'is suspended (in reset) in NDelius'
    -- when 'IS_PILOT_USER' then N/A
    -- when 'IS_TIER_D_TO_G' then N/A
    when 'ON_YOUTH_SENTENCE' then 'is in Tier A or B, on accredited programme, but on a Youth Sentence'
end
where ineligibility_message is not null;

--rollback alter table offender_eligibility_rule drop column ineligibility_auto_message, drop column ineligibility_message, drop column delius_outcome_code;
