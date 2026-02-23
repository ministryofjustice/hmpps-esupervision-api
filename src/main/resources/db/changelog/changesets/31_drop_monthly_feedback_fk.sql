--liquibase formatted sql

--changeset rob.catton:31_drop_monthly_feedback_fk splitStatements:false
ALTER TABLE monthly_feedback_stats
  DROP CONSTRAINT IF EXISTS monthly_feedback_stats_month_fkey;
