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

--rollback alter table offender_eligibility_rule drop column delius_outcome_code;
