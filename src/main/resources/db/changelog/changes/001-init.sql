--liquibase formatted sql

--changeset grammar-bot:001-create-chat-users
CREATE TABLE chat_users (
    id                BIGSERIAL PRIMARY KEY,
    chat_id           BIGINT       NOT NULL,
    username          VARCHAR(64),
    first_name        VARCHAR(128),
    ui_language       VARCHAR(8)   NOT NULL,
    mode              VARCHAR(16)  NOT NULL,
    target_language   VARCHAR(8)   NOT NULL,
    tone              VARCHAR(16)  NOT NULL,
    auto_explain      BOOLEAN      NOT NULL DEFAULT FALSE,
    onboarded         BOOLEAN      NOT NULL DEFAULT FALSE,
    source            VARCHAR(64),
    created_at        TIMESTAMP    NOT NULL,
    last_active_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uq_chat_users_chat_id UNIQUE (chat_id)
);

--rollback DROP TABLE chat_users;

--changeset grammar-bot:001-create-text-entries
CREATE TABLE text_entries (
    id                 BIGSERIAL PRIMARY KEY,
    chat_user_id       BIGINT       NOT NULL REFERENCES chat_users(id) ON DELETE CASCADE,
    source_text        TEXT         NOT NULL,
    source_message_id  INTEGER,
    result_message_id  INTEGER,
    mode               VARCHAR(16)  NOT NULL,
    target_language    VARCHAR(8)   NOT NULL,
    tone               VARCHAR(16)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    result_text        TEXT,
    detected_language  VARCHAR(8),
    action             VARCHAR(16),
    changes_json       TEXT,
    explanation_shown  BOOLEAN      NOT NULL DEFAULT FALSE,
    version            INTEGER      NOT NULL DEFAULT 0,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL
);

CREATE INDEX idx_text_entries_user_source_msg ON text_entries(chat_user_id, source_message_id);
CREATE INDEX idx_text_entries_created_at ON text_entries(created_at);

--rollback DROP TABLE text_entries;

--changeset grammar-bot:001-create-daily-usage
CREATE TABLE daily_usage (
    chat_id        BIGINT   NOT NULL,
    usage_date     DATE     NOT NULL,
    request_count  INTEGER  NOT NULL DEFAULT 0,
    PRIMARY KEY (chat_id, usage_date)
);

--rollback DROP TABLE daily_usage;

--changeset grammar-bot:001-create-ai-interactions
CREATE TABLE ai_interactions (
    id              BIGSERIAL PRIMARY KEY,
    chat_id         BIGINT       NOT NULL,
    kind            VARCHAR(16)  NOT NULL,
    request_text    TEXT,
    response_json   TEXT,
    outcome         VARCHAR(16)  NOT NULL,
    error_text      TEXT,
    latency_ms      BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL
);

CREATE INDEX idx_ai_interactions_created_at ON ai_interactions(created_at);
CREATE INDEX idx_ai_interactions_chat_id ON ai_interactions(chat_id);

--rollback DROP TABLE ai_interactions;
