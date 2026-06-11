--liquibase formatted sql

--changeset Maddie-Williams:67_update_welsh_default_questions splitStatements:false

update question_info qi
set question_template = 'Oes angen cymorth arnoch chi neu ydych chi eisiau rhoi gwybod i ni am unrhyw beth?'
from question q
where q.id = qi.question_id
  and q.uuid = '49639e7b-4c9d-54d4-8cdc-aa641d6d5c25'::uuid
  and qi.lang = 'cy-GB'
  and qi.question_template = 'Is there anything you need support with or want to let us know about?';

update question_info qi
set question_template = 'Sut ydych chi wedi bod yn teimlo ers i ni siarad ddiwethaf?'
from question q
where q.id = qi.question_id
  and q.uuid = 'b6e9bb4c-0186-5897-9dd3-0ebb4f59a583'::uuid
  and qi.lang = 'cy-GB'
  and qi.question_template = 'How have you been feeling since we last spoke?';


update question_info qi
set response_spec = $json$
{
  "hint": "Gallai hyn fod yn unrhyw beth rydych chi'n poeni amdano, yn cael trafferth ag ef neu ddim ond eisiau rhoi gwybod i ni.",
  "choices": [
    {
      "id": "MENTAL_HEALTH",
      "label": "Iechyd meddwl",
      "details_id": "mentalHealthSupport",
      "details_label": "Dywedwch wrthym beth rydych chi eisiau i ni ei wybod am iechyd meddwl (dewisol)",
      "domain_msg_head": "What they want us to know about mental health"
    },
    {
      "id": "ALCOHOL",
      "label": "Alcohol",
      "details_id": "alcoholSupport",
      "details_label": "Dywedwch wrthym beth rydych chi eisiau i ni ei wybod am alcohol (dewisol)",
      "domain_msg_head": "What they want us to know about alcohol"
    },
    {
      "id": "DRUGS",
      "label": "Cyffuriau",
      "details_id": "drugsSupport",
      "details_label": "Dywedwch wrthym beth rydych chi eisiau i ni ei wybod am gyffuriau (dewisol)",
      "domain_msg_head": "What they want us to know about drugs"
    },
    {
      "id": "MONEY",
      "label": "Arian",
      "details_id": "moneySupport",
      "details_label": "Dywedwch wrthym beth rydych chi eisiau i ni ei wybod am arian (dewisol)",
      "domain_msg_head": "What they want us to know about money"
    },
    {
      "id": "HOUSING",
      "label": "Tai",
      "details_id": "housingSupport",
      "details_label": "Dywedwch wrthym beth rydych chi eisiau i ni ei wybod am dai (dewisol)",
      "domain_msg_head": "What they want us to know about housing"
    },
    {
      "id": "EMPLOYMENT_EDU",
      "label": "Cyflogaeth ac addysg",
      "details_id": "employmentEduSupport",
      "details_label": "Dywedwch wrthym beth rydych chi eisiau i ni ei wybod am eich Cyflogaeth ac addysg (dewisol)",
      "domain_msg_head": "What they want us to know about employment and education"
    },
    {
      "id": "SUPPORT_SYSTEM",
      "label": "Perthnasoedd (teulu, ffrindiau, partner)",
      "details_id": "supportSystemSupport",
      "details_label": "Dywedwch wrthym beth rydych chi eisiau i ni ei wybod am eich perthnasoedd (dewisol)",
      "domain_msg_head": "What they want us to know about their relationships"
    },
    {
      "id": "OTHER",
      "label": "Rhywbeth arall",
      "details_id": "otherSupport",
      "details_label": "Dywedwch wrthym beth rydych chi eisiau i ni ei wybod (dewisol)",
      "domain_msg_head": "What they want us to know about (something else)"
    }
  ],
  "alternative": {
    "id": "NO_HELP",
    "label": "Na, nid oes angen unrhyw gymorth arnaf",
    "details_id": null,
    "details_label": null,
    "domain_msg_head": "They don't need support"
  },
  "placeholders": [],
  "domain_msg_key": "assistance",
  "domain_msg_head": "Anything they need support with or to let us know"
}
$json$::jsonb
from question q
where q.id = qi.question_id
  and q.uuid = '49639e7b-4c9d-54d4-8cdc-aa641d6d5c25'::uuid
  and qi.lang = 'cy-GB'
  and qi.response_spec ->> 'hint' = 'This could be anything you''re worrying about, struggling with or just want to let us know.';


update question_info qi
set response_spec = $json$
{
  "hint": "Meddyliwch am bethau fel a ydych chi wedi sylwi ar newid yn eich hwyliau a beth allai fod wedi achosi hyn.",
  "choices": [
    {
      "id": "VERY_WELL",
      "label": "Da iawn",
      "details_id": "mentalHealthComment",
      "details_label": "Dywedwch wrthym pam eich bod yn teimlo'n dda iawn (dewisol)",
      "domain_msg_head": "What they want us to know about how they have been feeling"
    },
    {
      "id": "WELL",
      "label": "Yn dda",
      "details_id": "mentalHealthComment",
      "details_label": "Dywedwch wrthym pam eich bod yn teimlo'n dda (dewisol)",
      "domain_msg_head": "What they want us to know about how they have been feeling"
    },
    {
      "id": "OK",
      "label": "Iawn",
      "details_id": "mentalHealthComment",
      "details_label": "Dywedwch wrthym pam eich bod yn teimlo'n iawn (dewisol)",
      "domain_msg_head": "What they want us to know about how they have been feeling"
    },
    {
      "id": "NOT_GREAT",
      "label": "Ddim yn grêt",
      "details_id": "mentalHealthComment",
      "details_label": "Dywedwch wrthym pam nad ydych chi'n teimlo'n grêt (dewisol)",
      "domain_msg_head": "What they want us to know about how they have been feeling"
    },
    {
      "id": "STRUGGLING",
      "label": "Cael trafferth",
      "details_id": "mentalHealthComment",
      "details_label": "Dywedwch wrthym pam eich bod chi'n cael trafferth (dewisol)",
      "domain_msg_head": "What they want us to know about how they have been feeling"
    }
  ],
  "message": {
    "html": "Os ydych chi angen siarad â rhywun ar frys am sut rydych chi'n teimlo, edrychwch ar <a href=\"https://www.nhs.uk/mental-health/feelings-symptoms-behaviours/behaviours/help-for-suicidal-thoughts/\" class=\"govuk-link\" target=\"_blank\">wefan y GIG i gael help (yn agor mewn tab newydd)</a>."
  },
  "placeholders": [],
  "domain_msg_head": "How they have been feeling"
}
$json$::jsonb
from question q
where q.id = qi.question_id
  and q.uuid = 'b6e9bb4c-0186-5897-9dd3-0ebb4f59a583'::uuid
  and qi.lang = 'cy-GB'
  and qi.response_spec ->> 'hint' = 'Think about things like if you have noticed a change in your mood and what may have caused this. ';

-- no rollback, it's a content update