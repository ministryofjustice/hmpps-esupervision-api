--liquibase formatted sql

--changeset roland.sadowski:48_update_hint_for_customisable_questions

update question_info
set response_spec = jsonb_set(response_spec, '{hint}', '"Add as much detail as you feel comfortable with, so we can support you in the best way."')
from (select qi.id, qi.question_id
      from question_info qi
               join question q on q.id = question_id
      where q.policy = 'CUSTOMISABLE'
     ) as to_update(id, question_id)
where question_info.id = to_update.id
