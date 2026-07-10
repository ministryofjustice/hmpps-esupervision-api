--liquibase formatted sql

--changeset roland.sadowski:70_add_rationale_column.sql splitStatements:false

alter table offender_setup_v2 add rationale text;

--rollback alter table offender_setup_v2 drop column rationale;
