--liquibase formatted sql

--changeset roland.sadowski:70_add_rationale_column.sql splitStatements:false

alter table offender add rationale text;

--rollback alter table offender drop column rationale;
