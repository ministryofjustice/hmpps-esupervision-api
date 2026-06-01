--liquibase formatted sql

--changeset roland.sadowski:50_add_question_list_assignment_index splitStatements:false

create index "idx_assignment_checkin_id" on question_list_assignment (checkin_id);

--rollback drop index if exists "idx_assignment_checkin_id";