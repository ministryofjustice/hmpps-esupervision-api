--liquibase formatted sql

--changeset rob.catton:22_create_monthly_stats_table
CREATE TABLE monthly_stats (
  month DATE PRIMARY KEY,
  users_activated BIGINT NOT NULL,
  users_deactivated BIGINT NOT NULL,
  completed_checkins BIGINT NOT NULL,
  not_completed_on_time BIGINT NOT NULL,
  total_hours_to_complete NUMERIC(12,2),
  total_completed_checkins_per_offender BIGINT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
