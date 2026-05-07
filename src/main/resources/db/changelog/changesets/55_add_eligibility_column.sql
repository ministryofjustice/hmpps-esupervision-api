--liquibase formatted sql

--changeset roland.sadowski:55_add_eligibility_column.sql splitStatements:false

create type eligibility_choice as enum ('REPLACE_F2F', 'SUPPLEMENT_F2F');

alter table offender_setup_v2 add eligibility_choice eligibility_choice;

--rollback alter table offender_setup_v2 drop column eligibility_choice;
--rollback drop type eligibility_choice;
