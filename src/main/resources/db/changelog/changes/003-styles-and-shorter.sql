--liquibase formatted sql

--changeset grammar-bot:003-limit-styles
UPDATE chat_users SET tone = 'NATURAL' WHERE tone NOT IN ('NATURAL', 'FORMAL', 'CASUAL');
UPDATE text_entries SET tone = 'NATURAL' WHERE tone NOT IN ('NATURAL', 'FORMAL', 'CASUAL');

--changeset grammar-bot:003-text-entries-shortest
ALTER TABLE text_entries ADD COLUMN shortest BOOLEAN NOT NULL DEFAULT FALSE;
--rollback ALTER TABLE text_entries DROP COLUMN shortest;
