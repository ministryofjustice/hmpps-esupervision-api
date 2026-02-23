--liquibase formatted sql

--changeset rob.catton:33_add_provider_to_monthly_stats splitStatements:false

-- Add columns
ALTER TABLE monthly_stats
  ADD COLUMN provider_code VARCHAR(10),
  ADD COLUMN provider_description TEXT;

-- Clear table and repopulate via the job - no risk as all this data is computed from other tables
TRUNCATE TABLE monthly_stats;

-- Drop old PK and replace with composite PK
ALTER TABLE monthly_stats
  DROP CONSTRAINT IF EXISTS monthly_stats_pkey;

ALTER TABLE monthly_stats
  ALTER COLUMN provider_code SET NOT NULL;

ALTER TABLE monthly_stats
  ALTER COLUMN provider_description SET NOT NULL;

ALTER TABLE monthly_stats
  ADD CONSTRAINT monthly_stats_pkey PRIMARY KEY (month, provider_code);

CREATE INDEX IF NOT EXISTS monthly_stats_month_idx ON monthly_stats(month);
CREATE INDEX IF NOT EXISTS monthly_stats_provider_code_idx ON monthly_stats(provider_code);
