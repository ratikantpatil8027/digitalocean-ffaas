-- Postgres-first schema (ARCHITECTURE §5). Quoted "key" for H2 reserved-word compatibility.
-- conditions uses JSON so the same script runs on H2 test profile; Postgres accepts JSON
-- (production storage is document-at-a-time; JSONB preferred on Postgres when migrating solely there).
CREATE TABLE feature_flags (
    id            UUID PRIMARY KEY,
    "key"         VARCHAR(64)  NOT NULL UNIQUE,
    name          VARCHAR(100) NOT NULL,
    description   VARCHAR(500),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    default_state BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE flag_rules (
    id                 UUID PRIMARY KEY,
    flag_id            UUID NOT NULL REFERENCES feature_flags(id) ON DELETE CASCADE,
    priority           INT  NOT NULL CHECK (priority >= 0),
    serve              BOOLEAN NOT NULL,
    rollout_percentage INT CHECK (rollout_percentage BETWEEN 0 AND 100),
    conditions         JSON NOT NULL,
    UNIQUE (flag_id, priority)
);

CREATE INDEX idx_flag_rules_flag_id ON flag_rules(flag_id);
