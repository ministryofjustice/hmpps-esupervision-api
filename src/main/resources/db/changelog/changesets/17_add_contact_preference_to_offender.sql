--liquibase formatted sql

--changeset dave.iles:17_add_contact_preference_to_offender-1  splitStatements:false
CREATE TYPE contact_type_v2 AS ENUM ('PHONE','EMAIL');

ALTER TABLE offender_v2
ADD COLUMN contact_preference contact_type_v2;