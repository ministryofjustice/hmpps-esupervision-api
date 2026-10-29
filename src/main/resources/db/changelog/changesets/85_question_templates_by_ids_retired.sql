--liquibase formatted sql

-- ESUP-2230: the by-ids lookup used to validate an assignment also reports whether each template
-- has been retired (see changeset 83), so the service needs one query rather than two.
-- The return type changes, so the function is dropped and recreated rather than replaced.

--changeset james.king:85_question_templates_by_ids_retired-1 splitStatements:false

drop function get_question_templates_by_ids(bigint[], text_language);

create function get_question_templates_by_ids(ids bigint[], p_lang text_language)
    returns table (
                      question_id bigint,
                      policy question_policy,
                      question_template text,
                      response_format response_format,
                      response_spec jsonb,
                      example text,
                      retired_at timestamp with time zone
                  ) as $$
begin
    return query
        select
            q.id, q.policy,
            qi.question_template, qi.response_format, qi.response_spec, coalesce(qi.example, qi2.example) as example,
            q.retired_at
        from question q
        join question_info qi on q.id = qi.question_id
        left join question_info qi2 on qi2.question_id = q.id
        where
            qi.lang = p_lang
            and qi2.lang != p_lang
            and q.id = any(ids)
        order by q.created_at;
end;
$$ LANGUAGE plpgsql STABLE;

--rollback drop function get_question_templates_by_ids(bigint[], text_language);
--rollback create function get_question_templates_by_ids(ids bigint[], p_lang text_language)
--rollback     returns table (
--rollback                       question_id bigint,
--rollback                       policy question_policy,
--rollback                       question_template text,
--rollback                       response_format response_format,
--rollback                       response_spec jsonb,
--rollback                       example text
--rollback                   ) as $$
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
--rollback             and qi2.lang != p_lang
--rollback             and q.id = any(ids)
--rollback         order by q.created_at;
--rollback end;
--rollback $$ LANGUAGE plpgsql STABLE;
