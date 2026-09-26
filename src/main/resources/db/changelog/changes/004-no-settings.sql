--liquibase formatted sql

--changeset grammar-bot:004-smart-mode-only
UPDATE chat_users SET mode = 'SMART', tone = 'NATURAL', auto_explain = FALSE;
