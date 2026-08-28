--liquibase formatted sql

--changeset roland.sadowski:77_offender_eligibility_rule_audit_event_type-1

alter table offender_eligibility_rule add column audit_event_type varchar(64);

--rollback alter table offender_eligibility_rule drop column audit_event_type;

--changeset roland.sadowski:77_offender_eligibility_rule_audit_event_type-2

update offender_eligibility_rule
set audit_event_type = 'OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS'
where code = 'HAS_ACTIVE_EVENT';

update offender_eligibility_rule
set audit_event_type = 'OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED'
where code = 'IS_CONTACT_SUSPENDED';

--rollback update offender_eligibility_rule set audit_event_type = null;
