--liquibase formatted sql

--changeset rob.catton:32_add_pdu_to_monthly_stats splitStatements:false

-- Add columns
ALTER TABLE monthly_stats
  ADD COLUMN pdu_code VARCHAR(10),
  ADD COLUMN pdu_description TEXT;

-- Clear table and repopulate via the job - no risk as all this data is computed from other tables
TRUNCATE TABLE monthly_stats;

-- Drop old PK and replace with composite PK
ALTER TABLE monthly_stats
  DROP CONSTRAINT IF EXISTS monthly_stats_pkey;

ALTER TABLE monthly_stats
  ALTER COLUMN pdu_code SET NOT NULL;

ALTER TABLE monthly_stats
  ALTER COLUMN pdu_description SET NOT NULL;

ALTER TABLE monthly_stats
  ADD CONSTRAINT monthly_stats_pkey PRIMARY KEY (month, pdu_code);

CREATE INDEX IF NOT EXISTS monthly_stats_month_idx ON monthly_stats(month);
CREATE INDEX IF NOT EXISTS monthly_stats_pdu_code_idx ON monthly_stats(pdu_code);
