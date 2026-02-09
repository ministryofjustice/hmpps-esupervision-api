--liquibase formatted sql

--changeset rob.catton:22_create_monthly_stats_table
CREATE TABLE monthly_stats (
  month DATE PRIMARY KEY,
  users_activated BIGINT NOT NULL,
  users_deactivated BIGINT NOT NULL,
  completed_checkins BIGINT NOT NULL,
  unique_checkin_crns BIGINT,
  not_completed_on_time BIGINT NOT NULL,
  total_hours_to_complete NUMERIC(12,2),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
