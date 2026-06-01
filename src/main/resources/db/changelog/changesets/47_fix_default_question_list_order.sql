--liquibase formatted sql

--changeset roland.sadowski:47_fix_default_question_list_order

-- reverse the order of the Default question list
UPDATE question_list_item
SET position = updated_values.reversed_pos
FROM (SELECT
          question_list_id as list_id,
          question_id as question_id,
          ROW_NUMBER() OVER (ORDER BY position DESC) as reversed_pos
      FROM question_list_item
      WHERE question_list_id = (select id from question_list where name = 'Default')
     ) as updated_values(list_id, question_id, reversed_pos)
WHERE question_list_item.question_list_id = updated_values.list_id
  and question_list_item.question_id = updated_values.question_id;
