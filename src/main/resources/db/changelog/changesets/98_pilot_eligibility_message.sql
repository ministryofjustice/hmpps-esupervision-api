--liquibase formatted sql

--changeset roland.sadowski:98_pilot_eligibility_message-1 splitStatements:false

update offender_eligibility_rule
set ineligibility_message = 'No active sentence.'
where rule_set = 'PILOT_2026_08' and code = 'HAS_ACTIVE_EVENT';

--rollback:
--rollback update offender_eligibility_rule
--rollback set ineligibility_message = 'there are no active probation events in NDelius'
--rollback where rule_set = 'PILOT_2026_08' and code = 'HAS_ACTIVE_EVENT';
