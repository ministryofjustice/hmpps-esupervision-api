--liquibase formatted sql

--changeset rob.catton:26_create_monthly_feedback_stats_table splitStatements:false
CREATE TABLE monthly_feedback_stats (
  month DATE NOT NULL REFERENCES monthly_stats(month) ON DELETE CASCADE,

  feedback_version INT NOT NULL,

  -- e.g. 'howEasy', 'gettingSupport', 'improvements'
  feedback_key TEXT NOT NULL,

  total BIGINT NOT NULL DEFAULT 0,

  -- JSON maps: { "veryEasy": 12, "notAnswered": 3, ... }
  counts JSONB NOT NULL DEFAULT '{}'::jsonb,

  -- JSON maps: { "veryEasy": 0.5217, "notAnswered": 0.1304, ... }
  pct JSONB NOT NULL DEFAULT '{}'::jsonb,

  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

  PRIMARY KEY (month, feedback_version, feedback_key)
);

CREATE INDEX monthly_feedback_stats_month_idx
  ON monthly_feedback_stats(month);

CREATE INDEX monthly_feedback_stats_version_idx
  ON monthly_feedback_stats(feedback_version);

CREATE INDEX monthly_feedback_stats_feedback_key_idx
  ON monthly_feedback_stats(feedback_key);

CREATE INDEX monthly_feedback_stats_month_version_idx
  ON monthly_feedback_stats(month, feedback_version);
