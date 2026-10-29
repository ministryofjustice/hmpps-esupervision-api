--liquibase formatted sql

--changeset hmpps:86_add_delius_outcome_code_to_eligibility_rules-1 splitStatements:false

alter table offender_eligibility_rule
    add column delius_outcome_code varchar(5);

update offender_eligibility_rule
set delius_outcome_code = case code
    when 'HAS_ACTIVE_EVENT' then 'ESPNA'
    when 'IS_RECALLED' then 'ESPRC'
    when 'IS_TIER_MISSING' then 'ESPME'
    when 'IS_TIER_PROVISIONAL' then 'ESPIT'
    when 'IN_FINAL_THIRD' then 'ESPFT'
    when 'IS_PRACTITIONER_ASSIGNED' then 'ESPNP'
    when 'HAS_DEVICE_RESTRICTION' then 'ESPDI'
    -- when 'IS_TIER_C' then 'ESPIT'
    when 'ON_ACCREDITED_PROGRAMME' then 'ESPAP'
    when 'IN_EARLY_ENGAGEMENT' then 'ESPEE'
end
where rule_set = 'OFFENDER_ELIGIBILITY'
  and code in (
    'HAS_ACTIVE_EVENT',
    'IS_RECALLED',
    'IS_TIER_MISSING',
    'IS_TIER_PROVISIONAL',
    'IN_FINAL_THIRD',
    'IS_PRACTITIONER_ASSIGNED',
    'HAS_DEVICE_RESTRICTION',
    'IS_TIER_C',
    'ON_ACCREDITED_PROGRAMME',
    'IN_EARLY_ENGAGEMENT'
  );

update offender_eligibility_rule
set delius_outcome_code = case code
    when 'HAS_ACTIVE_EVENT' then 'ESPNA'
    when 'IS_CONTACT_SUSPENDED' then 'ESPRS'
end
where rule_set = 'PILOT_2026_08'
  and code in (
      'HAS_ACTIVE_EVENT',
      'IS_CONTACT_SUSPENDED'
);


alter table offender_eligibility_rule
    add column ineligibility_message text,
    add column ineligibility_auto_message text;

update offender_eligibility_rule
set ineligibility_message = case
    when outcome_on_no_match = 'NOT_ELIGIBLE' then message_on_no_match
    when outcome_on_match = 'NOT_ELIGIBLE' then message_on_match
end;

alter table offender_eligibility_rule
    drop constraint offender_eligibility_rule_match_message_check,
    drop constraint offender_eligibility_rule_no_match_message_check
-- the columns will be dropped in a separate PR
--     drop column message_on_match,
--     drop column message_on_no_match
;

-- the messages should fit the wording of the email message:
update offender_eligibility_rule
set ineligibility_auto_message = case code
    when 'HAS_ACTIVE_EVENT' then 'has no active sentence'
    when 'IS_RECALLED' then 'has been recalled to prison'
    when 'IS_TIER_MISSING' then 'is missing a Tier' -- dev
    when 'IS_TIER_PROVISIONAL' then 'has a provisional Tier' -- dev
    when 'IN_FINAL_THIRD' then 'is in the final third of their probation sentence'
    when 'IS_PRACTITIONER_ASSIGNED' then 'has no practitioner assigned'
    when 'HAS_DEVICE_RESTRICTION' then 'has device restrictions'
    when 'IS_TIER_C' then 'is in Tier C'
    when 'ON_ACCREDITED_PROGRAMME' then 'is in Tier A or B and their accredited programme has ended'
    when 'IN_EARLY_ENGAGEMENT' then 'is in Tier A or B, on an accredited programme, but in early engagement'
    when 'IS_CONTACT_SUSPENDED' then 'is suspended (in reset) in NDelius'
    -- when 'IS_PILOT_USER' then N/A
    -- when 'IS_TIER_D_TO_G' then N/A
    when 'ON_YOUTH_SENTENCE' then 'is in Tier A or B, on accredited programme, but on a Youth Sentence'
end
where ineligibility_message is not null;

--rollback alter table offender_eligibility_rule drop column ineligibility_auto_message, drop column ineligibility_message, drop column delius_outcome_code;

--changeset hmpps:86_add_delius_outcome_code_to_eligibility_rules-2 splitStatements:false

-- update the stats function to handle 'OFFENDER_AUTO_DEACTIVATED_INELIGIBLE'

CREATE OR REPLACE FUNCTION refresh_monthly_stats(
    p_month DATE,
    p_start TIMESTAMPTZ,
    p_end   TIMESTAMPTZ
)
    RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    excluded_provider_code text := 'XXX';
BEGIN

    -- Some audit rows (e.g. an automated deactivation of a PoP in reset) are recorded
-- without practitioner org details. Attribute them to the provider on the CRN's
-- latest earlier audit row, rather than silently dropping them.
    WITH month_events AS (
        SELECT
            e.event_type,
            e.crn,
            e.time_to_submit_hours,
            COALESCE(e.provider_code, prev.provider_code) AS provider_code,
            COALESCE(e.provider_description, prev.provider_description) AS provider_description
        FROM event_audit_log_v2 e
                 LEFT JOIN LATERAL (
            SELECT p.provider_code, p.provider_description
            FROM event_audit_log_v2 p
            WHERE e.provider_code IS NULL
              AND p.crn = e.crn
              AND p.provider_code IS NOT NULL
              AND p.occurred_at <= e.occurred_at
            ORDER BY p.occurred_at DESC, p.id DESC
            LIMIT 1
            ) prev ON TRUE
        WHERE e.occurred_at >= p_start
          AND e.occurred_at <  p_end
    ),

-- Provider descriptions are optional, so fall back to the code rather than dropping
-- the provider (and its events) when none is recorded this month.
         providers_in_month AS (
             SELECT
                 provider_code,
                 COALESCE(MAX(provider_description), provider_code) AS provider_description
             FROM month_events
             WHERE provider_code IS NOT NULL
               AND provider_code <> excluded_provider_code
             GROUP BY provider_code
         ),

         users_by_provider AS (
             SELECT
                 provider_code,
                 COUNT(*) FILTER (WHERE event_type IN ('SETUP_COMPLETED', 'OFFENDER_REACTIVATED')) AS users_activated,
                 COUNT(*) FILTER (WHERE event_type IN ('OFFENDER_DEACTIVATED',
                                                       'OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED',
                                                       'OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS',
                                                       'OFFENDER_AUTO_DEACTIVATED_INELIGIBLE'))::BIGINT AS users_deactivated
             FROM month_events
             WHERE provider_code <> excluded_provider_code
             GROUP BY provider_code
         ),

         checkins_by_provider AS (
             SELECT
                 provider_code,
                 COUNT(*) FILTER (WHERE event_type = 'CHECKIN_SUBMITTED')::BIGINT AS completed_checkins,
                 COUNT(*) FILTER (WHERE event_type = 'CHECKIN_EXPIRED')::BIGINT AS not_completed_on_time,
                 COALESCE(SUM(time_to_submit_hours) FILTER (WHERE event_type = 'CHECKIN_SUBMITTED'), 0)::NUMERIC AS total_hours_to_complete,
                 COUNT(DISTINCT crn) FILTER (WHERE event_type = 'CHECKIN_SUBMITTED')::BIGINT AS unique_checkin_crns
             FROM month_events
             WHERE provider_code <> excluded_provider_code
             GROUP BY provider_code
         ),

         rows_to_upsert AS (
             SELECT
                 p_month AS month,
                 p.provider_code,
                 p.provider_description,

                 COALESCE(u.users_activated, 0) AS users_activated,
                 COALESCE(u.users_deactivated, 0) AS users_deactivated,

                 COALESCE(c.completed_checkins, 0) AS completed_checkins,
                 COALESCE(c.unique_checkin_crns, 0) AS unique_checkin_crns,
                 COALESCE(c.not_completed_on_time, 0) AS not_completed_on_time,
                 COALESCE(c.total_hours_to_complete, 0)::NUMERIC AS total_hours_to_complete,

                 now() AS updated_at
             FROM providers_in_month p
                      LEFT JOIN users_by_provider u ON u.provider_code = p.provider_code
                      LEFT JOIN checkins_by_provider c ON c.provider_code = p.provider_code
         )

    INSERT INTO monthly_stats (
        month,
        provider_code,
        provider_description,
        users_activated,
        users_deactivated,
        completed_checkins,
        unique_checkin_crns,
        not_completed_on_time,
        total_hours_to_complete,
        updated_at
    )
    SELECT
        month,
        provider_code,
        provider_description,
        users_activated,
        users_deactivated,
        completed_checkins,
        unique_checkin_crns,
        not_completed_on_time,
        total_hours_to_complete,
        updated_at
    FROM rows_to_upsert
    ON CONFLICT (month, provider_code)
    DO UPDATE SET
      provider_description = EXCLUDED.provider_description,
      users_activated = EXCLUDED.users_activated,
      users_deactivated = EXCLUDED.users_deactivated,
      completed_checkins = EXCLUDED.completed_checkins,
      unique_checkin_crns = EXCLUDED.unique_checkin_crns,
      not_completed_on_time = EXCLUDED.not_completed_on_time,
      total_hours_to_complete = EXCLUDED.total_hours_to_complete,
      updated_at = now();

END;
$$;
