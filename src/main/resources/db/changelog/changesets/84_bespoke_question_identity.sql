--liquibase formatted sql

-- ESUP-2230: bespoke questions get an author-aware identity.
-- define_custom_question derives question.uuid from the text alone, and uk_question_uuid is global,
-- so two practitioners writing the same bespoke text (or text matching a SYSTEM template) would
-- collide on insert. SYSTEM template UUIDs are unchanged; only bespoke questions use this function.

--changeset james.king:84_bespoke_question_identity-1 splitStatements:false

create or replace function define_bespoke_question(
    p_author varchar(255),
    p_text text,
    p_spec jsonb
) returns bigint as $$
declare
    v_uuid uuid := uuid_generate_v5('A2D48E83-E912-43CB-8F64-0EAE9731D324', p_author || E'\n' || p_text);
    v_question_id bigint;
begin
    if p_author = 'SYSTEM' then
        raise exception 'Bespoke questions cannot be authored by SYSTEM';
    end if;

    -- Re-sending the same text for the same author reuses the existing question.
    select id into v_question_id from question where uuid = v_uuid;
    if found then
        return v_question_id;
    end if;

    -- Two transactions can both miss the lookup above. The insert is atomic on uk_question_uuid:
    -- the loser waits for the winner to commit, inserts nothing, and returns the winner's row.
    insert into question (author, policy, uuid)
    values (p_author, 'CUSTOMISABLE'::question_policy, v_uuid)
    on conflict (uuid) do nothing
    returning id into v_question_id;

    if v_question_id is null then
        select id into v_question_id from question where uuid = v_uuid;
        return v_question_id;
    end if;

    -- Both language rows carry the text as written: a bespoke question is asked verbatim.
    -- Only the transaction that created the question writes them.
    insert into question_info (question_id, lang, question_template, response_format, response_spec, example)
    values
        (v_question_id, 'en-GB'::text_language, p_text, 'TEXT'::response_format, p_spec, null),
        (v_question_id, 'cy-GB'::text_language, p_text, 'TEXT'::response_format, p_spec, null);

    return v_question_id;
end;
$$ language plpgsql;

--rollback drop function define_bespoke_question(varchar(255), text, jsonb);
