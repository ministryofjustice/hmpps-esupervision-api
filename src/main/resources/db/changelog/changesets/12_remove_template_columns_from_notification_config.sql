-- liquibase formatted sql

-- changeset denis:12_remove_notification_config_v2-1 splitStatements:false
-- Drop notification_config_v2 table entirely
-- Templates and enabled flags are now loaded from application.yml configuration (like V1)
-- This avoids DB calls per notification
DROP TABLE IF EXISTS "notification_config_v2";
