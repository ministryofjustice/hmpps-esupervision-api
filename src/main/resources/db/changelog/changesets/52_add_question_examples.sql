--liquibase formatted sql

--changeset roland.sadowski:52_add_question_examples-1 splitStatements:false

CREATE OR REPLACE FUNCTION fn_update_question_spec_with_example(
    p_question_uuid uuid,
    p_examples jsonb
)
    RETURNS INT AS $$
DECLARE
    affected_rows INT;
BEGIN
    -- note: we're updating both language versions - atm we both we don't have
    -- translations so both variants hold en-GB text
    UPDATE question_info qi
    SET
        --example_placeholder = p_examples,
        response_spec = jsonb_set(qi.response_spec, '{placeholders_examples}', p_examples->'replacements'),
        updated_at = now()
    FROM (SELECT id FROM question WHERE uuid = p_question_uuid) AS q
    WHERE qi.question_id = q.id;

    -- Capture the number of rows updated
    GET DIAGNOSTICS affected_rows = ROW_COUNT;

    RETURN affected_rows;
END;
$$ LANGUAGE plpgsql;

--rollback drop function fn_update_question_spec_with_example;

--changeset roland.sadowski:52_add_question_examples-2 splitStatements:false

select fn_update_question_spec_with_example(
       '35d40bed-875b-5b2e-a30c-2aa9a088e161'::uuid,
           $${
               "replacements": [
                  {"thing": "your unpaid work"},
                  {"thing": "your home life"}
               ]
           }$$::jsonb
);

select fn_update_question_spec_with_example(
       '8f110f57-e18c-5576-b949-d9f60d814fad'::uuid,
       $${
         "replacements": [
           {"thing": "in your relationship with your parent"},
           {"thing": "with your health"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       'c1433bb0-a661-58f2-a01f-f459f0d835ca'::uuid,
       $${
         "replacements": [
           {"thing": "your university course going"},
           {"thing": "your application for housing going"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       '74d33d12-925b-5ce2-96ac-5ae844dd4ad4'::uuid,
       $${
         "replacements": [
           {"thing": "your visit to see your family"},
           {"thing": "your job interview"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
   '24e3a3b3-80b3-5e55-b027-e53ce426a9b4'::uuid,
   $${
     "replacements": [
       {"thing": "your housing situation"},
       {"thing": "finding a new job"}
     ]
   }$$::jsonb
);

select fn_update_question_spec_with_example(
       '2b042554-cb44-5da4-9bca-5090227b73ea'::uuid,
       $${
         "replacements": [
           {"thing": "get an appointment with the service we referred you to since we last spoke"},
           {"thing": "sort out a new flat"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       '9fae71ca-a03d-54e7-bf88-5e63668f70bf'::uuid,
       $${
         "replacements": [
           {"thing": "your home address"},
           {"thing": "the vehicle you drive"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       '6c69ef33-a2ec-5f27-b5ff-b66428bbca3d'::uuid,
       $${
         "replacements": [
           {"thing": "homelessness prevention about your housing application"},
           {"thing": "the doctors about your recent appointment"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       '61c5c0a7-4511-5493-82f4-8ddbb9d3a287'::uuid,
       $${
         "replacements": [
           {"thing": "see your family"},
           {"thing": "your support group"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       'ba6078cc-e540-5216-be29-511cff0c2fc8'::uuid,
       $${
         "replacements": [
           {"thing": "your GP about your health issue"},
           {"thing": "the council about your housing application"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       'efd535ba-8fad-5381-bf10-5de19ec10210'::uuid,
       $${
         "replacements": [
           {"thing": "your home life"},
           {"thing": "your health"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       '04a65bc8-4969-544f-b912-5459fdb59d3e'::uuid,
       $${
         "replacements": [
           {"thing": "taking the medication you were given by your doctor"},
           {"thing": "in a relationship with someone"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       'ad1b2ba1-fb73-5383-a0eb-e1a5d26dc3b6'::uuid,
       $${
         "replacements": [
           {"thing": "your family and and home life"},
           {"thing": "your work"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       '67785061-43bc-5a7a-a436-1914bcfb42db'::uuid,
       $${
         "replacements": [
           {"thing": "you to find a new job"},
           {"thing": "you to change your current situation at home"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       '2dd89a71-8649-58bd-bbe8-bb4d42f71393'::uuid,
       $${
         "replacements": [
           {"thing": "need us to contact you about your recent appointment"},
           {"thing": "want any support with you benefits application"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       '13f6daee-15ee-5746-afc2-65e3f147905a'::uuid,
       $${
         "replacements": [
           {"thing": "your job application"},
           {"thing": "applying for a new house"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       '8f66a1f4-9466-502a-904c-c8c881c6a10b'::uuid,
       $${
         "replacements": [
           {"thing": "if we referred you to a therapy service"},
           {"thing": "to speak to someone about your financial situation"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       'fcb3d562-f173-5b0c-9b1f-e7a5ed1960ee'::uuid,
       $${
         "replacements": [
           {"thing": "get a doctor's appointment"},
           {"thing": "fill in your housing application form"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       '7586d463-931e-58f0-9b5e-e434796ef330'::uuid,
       $${
         "replacements": [
           {"thing": "college"},
           {"thing": "at home"}
         ]
       }$$::jsonb
);

select fn_update_question_spec_with_example(
       '0c2611c8-bfce-57fe-b263-6d5a5751b08a'::uuid,
       $${
         "replacements": [
           {"thing": "your home life"},
           {"thing": "your financial situation"}
         ]
       }$$::jsonb
);

--rollback update question_info set example_full = NULL;

--changeset roland.sadowski:52_add_question_examples-3 splitStatements:false

UPDATE question_info qi
SET
    response_spec = jsonb_set(qi.response_spec, '{alternative, label}', '"No, I do not need any support"'::jsonb),
    updated_at = now()
FROM (SELECT id FROM question WHERE uuid = '49639e7b-4c9d-54d4-8cdc-aa641d6d5c25'::uuid) AS q
WHERE qi.question_id = q.id;

UPDATE question_info qi
SET
    response_spec = jsonb_set(qi.response_spec, '{alternative, id}', '"NO_HELP"'::jsonb),
    updated_at = now()
FROM (SELECT id FROM question WHERE uuid = '49639e7b-4c9d-54d4-8cdc-aa641d6d5c25'::uuid) AS q
WHERE qi.question_id = q.id;

-- no rollback