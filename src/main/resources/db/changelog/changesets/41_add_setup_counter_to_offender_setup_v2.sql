--liquibase formatted sql

--changeset richard.birch:41_add_setup_counter_to_offender_setup_v2 splitStatements:false
ALTER TABLE offender_setup_v2
    ADD COLUMN setup_counter INT NOT NULL DEFAULT 1;

--rollback ALTER TABLE offender_setup_v2 DROP COLUMN setup_counter;
