--liquibase formatted sql

--changeset roland.sadowski:54_question_fixes-1 splitStatements:false

update question_info qi
set
    response_spec = jsonb_set(qi.response_spec, '{placeholders_examples,1,thing}', '"home"')
from question q
where
    q.id = qi.question_id
    and q.uuid = '7586d463-931e-58f0-9b5e-e434796ef330'::uuid;

update question_info qi
set
    response_spec = jsonb_set(qi.response_spec, '{placeholders_examples,0,thing}', '"in your relationship with your partner"')
from question q
where
    q.id = qi.question_id
    and q.uuid = '8f110f57-e18c-5576-b949-d9f60d814fad'::uuid;

-- no rollback, it's a content update