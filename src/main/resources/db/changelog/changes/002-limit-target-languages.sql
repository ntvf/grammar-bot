--liquibase formatted sql

--changeset grammar-bot:002-limit-target-languages
UPDATE chat_users SET target_language = 'EN'
WHERE target_language NOT IN ('EN', 'DE', 'ES', 'IT', 'FR', 'PL', 'UK');
