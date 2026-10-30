--liquibase formatted sql

-- ESUP-2230: practitioners should only be offered five customisable questions in a fixed order.
-- The other fifteen SYSTEM templates are retired rather than deleted: question_list_item rows from
-- previously assigned check ins still reference them, and historic check ins must keep rendering.

--changeset james.king:83_retire_unused_custom_question_templates-1 splitStatements:false

alter table question
    add column display_order integer null,
    add column retired_at timestamp with time zone null;

comment on column question.display_order is 'Order in which customisable templates are offered to practitioners; nulls sort last';
comment on column question.retired_at is 'When set, the template is no longer offered as a choice but stays resolvable for existing assignments';

--rollback alter table question drop column display_order, drop column retired_at;

--changeset james.king:83_retire_unused_custom_question_templates-2 splitStatements:false

create or replace function get_question_templates(p_lang text_language, p_author varchar(255), p_policy question_policy default 'CUSTOMISABLE'::question_policy)
    returns table(question_id bigint, policy question_policy, question_template text, response_format response_format, response_spec jsonb, example text)
    stable
    language plpgsql
as
$$
begin
    return query
        select
            q.id, q.policy,
            qi.question_template, qi.response_format, qi.response_spec, coalesce(qi.example, qi2.example) as example
        from question q
        join question_info qi on q.id = qi.question_id
        left join question_info qi2 on qi2.question_id = q.id
        where
            qi.lang = p_lang
          and qi2.lang != p_lang
          and q.author = p_author
          and q.policy = p_policy
          and q.retired_at is null
        order by q.display_order nulls last, q.created_at;
end;
$$;

--rollback create or replace function get_question_templates(p_lang text_language, p_author varchar(255), p_policy question_policy default 'CUSTOMISABLE'::question_policy)
--rollback     returns table(question_id bigint, policy question_policy, question_template text, response_format response_format, response_spec jsonb, example text)
--rollback     stable
--rollback     language plpgsql
--rollback as
--rollback $$
--rollback begin
--rollback     return query
--rollback         select
--rollback             q.id, q.policy,
--rollback             qi.question_template, qi.response_format, qi.response_spec, coalesce(qi.example, qi2.example) as example
--rollback         from question q
--rollback         join question_info qi on q.id = qi.question_id
--rollback         left join question_info qi2 on qi2.question_id = q.id
--rollback         where
--rollback             qi.lang = p_lang
--rollback           and qi2.lang != p_lang
--rollback           and q.author = p_author
--rollback           and q.policy = p_policy
--rollback         order by q.created_at;
--rollback end;
--rollback $$;

--changeset james.king:83_retire_unused_custom_question_templates-3 splitStatements:false

-- The five templates that stay, in the order they are offered.
update question q
set display_order = ordered.position
from (
    values
        ('c1433bb0-a661-58f2-a01f-f459f0d835ca'::uuid, 1), -- How is {{thing}}?
        ('35d40bed-875b-5b2e-a30c-2aa9a088e161'::uuid, 2), -- How has {{thing}} been going recently?
        ('2b042554-cb44-5da4-9bca-5090227b73ea'::uuid, 3), -- Have you been able to {{thing}}?
        ('8f110f57-e18c-5576-b949-d9f60d814fad'::uuid, 4), -- How have things been feeling {{thing}} recently?
        ('efd535ba-8fad-5381-bf10-5de19ec10210'::uuid, 5)  -- Has anything changed with {{thing}} recently?
) as ordered(uuid, position)
where q.uuid = ordered.uuid
  and q.author = 'SYSTEM'
  and q.policy = 'CUSTOMISABLE';

-- The fifteen SYSTEM templates no longer offered.
update question q
set retired_at = now()
where q.author = 'SYSTEM'
  and q.policy = 'CUSTOMISABLE'
  and q.retired_at is null
  and q.uuid in (
        '74d33d12-925b-5ce2-96ac-5ae844dd4ad4', -- How was {{thing}}?
        '24e3a3b3-80b3-5e55-b027-e53ce426a9b4', -- How can we best support you with {{thing}}?
        '6c69ef33-a2ec-5f27-b5ff-b66428bbca3d', -- Have you heard back from {{thing}}?
        '61c5c0a7-4511-5493-82f4-8ddbb9d3a287', -- Have you been to {{thing}} recently?
        'ba6078cc-e540-5216-be29-511cff0c2fc8', -- Have you had any recent contact with {{thing}}?
        '9fae71ca-a03d-54e7-bf88-5e63668f70bf', -- Have you changed {{thing}} recently?
        '04a65bc8-4969-544f-b912-5459fdb59d3e', -- Are you currently {{thing}}?
        'ad1b2ba1-fb73-5383-a0eb-e1a5d26dc3b6', -- Are there any stresses around {{thing}} that we could help with?
        '67785061-43bc-5a7a-a436-1914bcfb42db', -- Is there anything that might help {{thing}}?
        '2dd89a71-8649-58bd-bbe8-bb4d42f71393', -- Do you {{thing}}?
        '13f6daee-15ee-5746-afc2-65e3f147905a', -- Would you like some support with {{thing}}?
        '8f66a1f4-9466-502a-904c-c8c881c6a10b', -- Would you find it helpful {{thing}}?
        'fcb3d562-f173-5b0c-9b1f-e7a5ed1960ee', -- Were you able to {{thing}} since we last spoke?
        '7586d463-931e-58f0-9b5e-e434796ef330', -- What have you been doing at {{thing}} recently?
        '0c2611c8-bfce-57fe-b263-6d5a5751b08a'  -- What can we do to help with {{thing}}?
  );

--rollback update question set display_order = null, retired_at = null where author = 'SYSTEM' and policy = 'CUSTOMISABLE';
