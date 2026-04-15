--liquibase formatted sql

--changeset roland.sadowski:44_custom_questions_definitions splitStatements:false

select define_custom_question(
               'SYSTEM',
               'How has {{thing}} been going recently?',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'unpaid work, college course, work, apprenticeship, university course, sentence plan, training',
               'How has {{thing}} been going recently?',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'unpaid work, college course, work, apprenticeship, university course, sentence plan, training'
       );

select define_custom_question(
               'SYSTEM',
               'How have things been feeling {{thing}} recently? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'home, work, relationships with family, appointments with other bodies, physical or mental health, recovery journey',
               'How have things been feeling {{thing}} recently? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'home, work, relationships with family, appointments with other bodies, physical or mental health, recovery journey'
       );

select define_custom_question(
               'SYSTEM',
               'How is {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'physical or mental health, recovery, family relationships, relationship with partner, being a new parent, starting a new course, work',
               'How is {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'physical or mental health, recovery, family relationships, relationship with partner, being a new parent, starting a new course, work'
       );

select define_custom_question(
               'SYSTEM',
               'How was {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'job interview, doctors appointment, homelessness appointment, landlord visit, birthday',
               'How was {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'job interview, doctors appointment, homelessness appointment, landlord visit, birthday'
       );

select define_custom_question(
               'SYSTEM',
               'How can we best support you with {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'job interview, appointment, hospital visit, recovery journey, being a new parent, benefits assessment, financial situation, physical or mental health',
               'How can we best support you with {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'job interview, appointment, hospital visit, recovery journey, being a new parent, benefits assessment, financial situation, physical or mental health'
       );

select define_custom_question(
               'SYSTEM',
               'Have you been able to {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'get an appointment, speak with the housing office, benefits office, home office, council, chase up your application, change jobs, collect medication, complete a form',
               'Have you been able to {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'get an appointment, speak with the housing office, benefits office, home office, council, chase up your application, change jobs, collect medication, complete a form'
       );

select define_custom_question(
               'SYSTEM',
               'Have you heard back from {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'doctors, council, police, social services, benefits office',
               'Have you heard back from {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'doctors, council, police, social services, benefits office'
       );

select define_custom_question(
               'SYSTEM',
               'Have you been to {{thing}} recently? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'a place, an appointment, a group, a service',
               'Have you been to {{thing}} recently? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'a place, an appointment, a group, a service'
       );

select define_custom_question(
               'SYSTEM',
               'Have you had any recent contact with {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'police, doctors, social services, alcohol or drug recovery referrals, council, family members',
               'Have you had any recent contact with {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'police, doctors, social services, alcohol or drug recovery referrals, council, family members'
       );

select define_custom_question(
               'SYSTEM',
               'Have you changed {{thing}} recently? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'address, living situation, vehicle',
               'Have you changed {{thing}} recently? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'address, living situation, vehicle'
       );

select define_custom_question(
               'SYSTEM',
               'Has anything changed with {{thing}} recently?',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'living situation, support network, caring responsibilities,  recovery journey, housing or employment, finances,  relationship, responsibilities at home',
               'Has anything changed with {{thing}} recently?',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'living situation, support network, caring responsibilities,  recovery journey, housing or employment, finances,  relationship, responsibilities at home'
       );

select define_custom_question(
               'SYSTEM',
               'Are you currently {{thing}}?',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'homeless, looking for work, in a new relationship, in contact with a person or service, waiting for housing',
               'Are you currently {{thing}}?',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'homeless, looking for work, in a new relationship, in contact with a person or service, waiting for housing'
       );

select define_custom_question(
               'SYSTEM',
               'Are there any stresses around {{thing}} that we could help with?',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'work, family, finances, alcohol or drug recovery, physical or mental health, housing, caring for a person, relationships or friendships, probation, sentence plan, training, university, accredited programme, unpaid work',
               'Are there any stresses around {{thing}} that we could help with?',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'work, family, finances, alcohol or drug recovery, physical or mental health, housing, caring for a person, relationships or friendships, probation, sentence plan, training, university, accredited programme, unpaid work'
       );

select define_custom_question(
               'SYSTEM',
               'Is there anything that might help {{thing}}?',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'you feel more supported, grounded, connected to the community, find work, manage your finances better, recovery from health issue, get back on track, with a process',
               'Is there anything that might help {{thing}}?',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'you feel more supported, grounded, connected to the community, find work, manage your finances better, recovery from health issue, get back on track, with a process'
       );

select define_custom_question(
               'SYSTEM',
               'Do you {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'know what''s happening with a person, situation, referral, feel safe where you are right now, have an update about something',
               'Do you {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'know what''s happening with a person, situation, referral, feel safe where you are right now, have an update about something'
       );

select define_custom_question(
               'SYSTEM',
               'Would you like some support with {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'filling in a form, recovery journey, alcohol or drug addiction, a challenging situation, home life, physical or mental health',
               'Would you like some support with {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'filling in a form, recovery journey, alcohol or drug addiction, a challenging situation, home life, physical or mental health'
       );

select define_custom_question(
               'SYSTEM',
               'Would you find it helpful {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'to be referred to a service, to sit and fill in a form together, to speak with someone about something',
               'Would you find it helpful {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'to be referred to a service, to sit and fill in a form together, to speak with someone about something'
       );

select define_custom_question(
               'SYSTEM',
               'Were you able to {{thing}} since we last spoke?',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'go to an appointment, fill in a form, speak with a service, complete a task, apply for a job or house, find a homeless shelter',
               'Were you able to {{thing}} since we last spoke?',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'go to an appointment, fill in a form, speak with a service, complete a task, apply for a job or house, find a homeless shelter'
       );

select define_custom_question(
               'SYSTEM',
               'What have you been doing at {{thing}} recently?',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'unpaid work, work, alcohol or drug recovery service, group session, accredited programme, home, your hobby or interest',
               'What have you been doing at {{thing}} recently?',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'unpaid work, work, alcohol or drug recovery service, group session, accredited programme, home, your hobby or interest'
       );

select define_custom_question(
               'SYSTEM',
               'What can we do to help with {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'an appointment, challenges in life, physical or mental health struggles, addiction, relationship breakdown, moving house, change of circumstances, being a new parent',
               'What can we do to help with {{thing}}? ',
               $${"hint":"Hint for the question about the {{thing}}","placeholders":["thing"]}$$::jsonb,
               'an appointment, challenges in life, physical or mental health struggles, addiction, relationship breakdown, moving house, change of circumstances, being a new parent'
       );

--rollback delete * from custom_questions where author = 'SYSTEM' and question_policy = 'CUSTOMISABLE';