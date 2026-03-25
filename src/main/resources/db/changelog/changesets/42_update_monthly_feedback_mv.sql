--liquibase formatted sql

--changeset dave.iles:42_update_monthly_feedback_mv-1 splitStatements:false
DROP FUNCTION get_summary(date,date,text);

CREATE OR REPLACE FUNCTION get_summary(from_month DATE, to_month DATE, p_row_type TEXT DEFAULT 'ALL')
    RETURNS TABLE (
                      provider_code VARCHAR(10),
                      provider_description VARCHAR(255),
                      signed_up BIGINT,
                      active_users BIGINT,
                      inactive_users BIGINT,
                      total_signed_up BIGINT,
                      total_active_users BIGINT,
                      total_inactive_users BIGINT,
                      completed_checkins BIGINT,
                      expired_checkins BIGINT,
                      avg_hours_to_complete DECIMAL,
                      avg_checkins_completed_per_person DECIMAL,
                      pct_active_users DECIMAL,
                      pct_inactive_users DECIMAL,
                      pct_active_users_total DECIMAL,
                      pct_inactive_users_total DECIMAL,
                      pct_completed_checkins DECIMAL,
                      pct_expired_checkins DECIMAL,
                      pct_signed_up DECIMAL,
                      updated_at TIMESTAMP WITH TIME ZONE
                  ) AS $$
BEGIN
    RETURN QUERY
        WITH range_stats AS (
            SELECT
                COALESCE(s.provider_code, 'ALL') as provider_code,
                COALESCE(SUM(s.completed_checkins), 0)::BIGINT AS range_completed,
                COALESCE(SUM(s.not_completed_on_time), 0)::BIGINT AS range_expired,
                COALESCE(SUM(s.total_hours_to_complete), 0)::NUMERIC AS range_hours,
                COALESCE(SUM(s.unique_checkin_crns), 0)::BIGINT AS range_unique_crns
            FROM (SELECT 1) dummy
            LEFT JOIN stats_summary_provider_month s ON s.month >= from_month AND s.month < to_month AND s.row_type = p_row_type
            GROUP BY s.provider_code
        ),
        latest_months AS (
             -- Identify the latest month for each provider in the range
             SELECT
                 s.provider_code,
                 MAX(s.month) as max_month
             FROM stats_summary_provider_month s
             WHERE s.month >= from_month AND s.month < to_month
               AND s.row_type = p_row_type
             GROUP BY s.provider_code
        ),
        latest_total as (
            SELECT COALESCE(MAX(s.total_signed_up), 0) as total_signed_up
            FROM (
                SELECT s.total_signed_up
                FROM stats_summary_provider_month s
                WHERE row_type = 'ALL' AND s.month >= from_month AND s.month < to_month
                ORDER BY month DESC
                LIMIT 1
            ) s
        ),
        latest_snapshot AS (
             SELECT
                 rs.provider_code,
                 COALESCE(s.signed_up, 0) as signed_up,
                 COALESCE(s.active_users, 0) as active_users,
                 COALESCE(s.inactive_users, 0) as inactive_users,
                 COALESCE(s.pct_active_users, 0) as pct_active_users,
                 COALESCE(s.pct_inactive_users, 0) as pct_inactive_users,
                 COALESCE(s.total_signed_up, 0) as total_signed_up,
                 COALESCE(s.total_active_users, 0) as total_active_users,
                 COALESCE(s.total_inactive_users, 0) as total_inactive_users,
                 COALESCE(s.pct_active_users_total, 0) as pct_active_users_total,
                 COALESCE(s.pct_inactive_users_total, 0) as pct_inactive_users_total,
                 COALESCE(s.provider_description, '') as provider_description,
                 COALESCE(s.updated_at, now()) as updated_at
             FROM range_stats rs
             LEFT JOIN latest_months lm ON rs.provider_code = lm.provider_code
             LEFT JOIN stats_summary_provider_month s ON s.provider_code = lm.provider_code AND s.month = lm.max_month AND s.row_type = p_row_type
        )
        SELECT
            rs.provider_code,
            ls.provider_description,
            ls.total_signed_up as total_signed_up,
            ls.total_active_users,
            ls.total_inactive_users,
            ls.signed_up,
            ls.active_users,
            ls.inactive_users,
            rs.range_completed as completed_checkins,
            rs.range_expired as expired_checkins,
            CASE WHEN rs.range_completed = 0 THEN 0
                ELSE ROUND(rs.range_hours / rs.range_completed, 4) END as avg_hours_to_complete,
            CASE WHEN rs.range_unique_crns = 0 THEN 0
                ELSE ROUND(rs.range_completed::NUMERIC / rs.range_unique_crns, 4) END as avg_checkins_completed_per_person,
            ls.pct_active_users,
            ls.pct_inactive_users,
            ls.pct_active_users_total,
            ls.pct_inactive_users_total,
            CASE WHEN (rs.range_completed + rs.range_expired) = 0 THEN 0
                 ELSE ROUND(rs.range_completed::NUMERIC / (rs.range_completed + rs.range_expired), 4) END as pct_completed_checkins,
            CASE WHEN (rs.range_completed + rs.range_expired) = 0 THEN 0
                 ELSE ROUND(rs.range_expired::NUMERIC / (rs.range_completed + rs.range_expired), 4) END as pct_expired_checkins,
            CASE WHEN lt.total_signed_up = 0 THEN 0
                ELSE ROUND(ls.total_signed_up::NUMERIC / lt.total_signed_up, 4) END as pct_signed_up_total,
            ls.updated_at
        FROM range_stats rs
        JOIN latest_snapshot ls ON rs.provider_code = ls.provider_code
        CROSS JOIN latest_total lt
        WHERE rs.provider_code IS NOT NULL OR p_row_type = 'ALL';
END;
$$ LANGUAGE plpgsql STABLE;