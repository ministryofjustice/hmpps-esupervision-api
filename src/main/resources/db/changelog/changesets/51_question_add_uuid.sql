--liquibase formatted sql

--changeset roland.sadowski:51_question_add_uuid-1 splitStatements:false

create extension if not exists "uuid-ossp";

alter table question add column "uuid" UUID;

--rollback alter table question drop column "uuid";

--changeset roland.sadowski:51_question_add_uuid-2 splitStatements:false

update question as q
set uuid = uuid_generate_v5(
           'A2D48E83-E912-43CB-8F64-0EAE9731D324',
            qi.question_template
           )
from question_info as qi
where q.id = qi.question_id;

--changeset roland.sadowski:51_question_add_uuid-3 splitStatements:false

alter table question
    alter column uuid set not null,
    add constraint uk_question_uuid unique (uuid);

--rollback alter table question drop constraint uk_question_uuid;

--changeset roland.sadowski:51_question_add_uuid-4 splitStatements:false

alter function
    define_system_question(p_response_format response_format, en_question_template text, en_spec jsonb, en_example text, cy_question_template text, cy_spec jsonb, cy_example text)
rename to define_system_question_without_uuid;

create or replace function define_system_question(
    p_response_format response_format,
    en_question_template text,
    en_spec jsonb,
    en_example text,
    cy_question_template text,
    cy_spec jsonb,
    cy_example text
) returns bigint as $$
declare
    new_question_id bigint;
begin
    insert into question (author, uuid)
    values (
            'SYSTEM',
            uuid_generate_v5(
                    'A2D48E83-E912-43CB-8F64-0EAE9731D324',
                    en_question_template
            )
           )
    returning id into new_question_id;

    insert into question_info (
        question_id,
        lang,
        question_template,
        response_format,
        response_spec,
        example
    )
    select
        new_question_id,
        q.lang,
        q.template,
        p_response_format,
        q.spec,
        q.example
    from (
             values
                 ('en-GB'::text_language, en_question_template, en_spec, en_example),
                 ('cy-GB'::text_language, cy_question_template, cy_spec, cy_example)
         ) as q(lang, template, spec, example);

    return new_question_id;
end;
$$ language plpgsql

--rollback drop function define_system_question;
--rollback alter function define_system_question_without_uuid(p_response_format response_format, en_question_template text, en_spec jsonb, en_example text, cy_question_template text, cy_spec jsonb, cy_example text) rename to define_system_question;

--changeset roland.sadowski:51_question_add_uuid-5 splitStatements:false

alter function define_custom_question(p_author varchar(255), en_question_template text, en_spec jsonb, en_example text, cy_question_template text, cy_spec jsonb, cy_example text) rename to define_custom_question_without_uuid;

create or replace function define_custom_question(
    p_author varchar(255),
    en_question_template text default null,
    en_spec jsonb default null,
    en_example text default null,
    cy_question_template text default null,
    cy_spec jsonb default null,
    cy_example text default null
) returns integer as $$
declare
    new_question_id integer;
begin
    insert into question (author, policy, uuid)
    values (
            p_author,
            'CUSTOMISABLE'::question_policy,
            uuid_generate_v5(
                    'A2D48E83-E912-43CB-8F64-0EAE9731D324',
                    coalesce(en_question_template, cy_question_template)
            ))
    returning id into new_question_id;

    insert into question_info (
        question_id,
        lang,
        question_template,
        response_format,
        response_spec,
        example
    )
    select
        new_question_id,
        q.lang,
        q.template,
        'TEXT'::response_format,
        q.spec,
        q.example
    from (
             values
                 ('en-GB'::text_language, en_question_template, en_spec, en_example),
                 ('cy-GB'::text_language, cy_question_template, cy_spec, cy_example)
         ) as q(lang, template, spec, example)
    where q.template is not null; -- This ensures only provided versions are saved

    if not exists (select 1 from question_info where question_id = new_question_id) then
        raise exception 'At least one localization (EN or CY) must be provided.';
    end if;

    return new_question_id;
end;
$$ language plpgsql;

--rollback drop function define_custom_question;
--rollback alter function define_custom_question_without_uuid(p_author varchar(255), en_question_template text default null, en_spec jsonb default null, en_example text default null, cy_question_template text default null, cy_spec jsonb default null, cy_example text default null) rename to define_custom_question;
