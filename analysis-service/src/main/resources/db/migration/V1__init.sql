-- V1 of analysis-service (task 4.1 step 2b): its tables in its own schema "analysis", owned by analysis_svc.
-- Rule: never edit this file after it has been applied anywhere. Add V2__..., V3__... instead.
-- The same tables the app's V1-V4 made in "public" (project, code_file, code_chunk, llm_call, outbox_event,
-- processed_event), in one file; the interview tables stay with the app. Those old "public" tables are left as they
-- were: their data is not copied here (D-026). Needs db/bootstrap.sql first (the schema and pgvector).

-- ---------------------------------------------------------------------------
-- Projects (one row per analyzed repo + commit)
-- ---------------------------------------------------------------------------
CREATE TABLE project (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    github_url            TEXT        NOT NULL,           -- normalized: https://github.com/{owner}/{repo}
    owner                 TEXT        NOT NULL,
    repo_name             TEXT        NOT NULL,
    commit_sha            VARCHAR(40) NOT NULL,
    default_branch        TEXT,
    status                VARCHAR(20) NOT NULL,
    failure_reason        TEXT,
    goal                  TEXT,                           -- candidate-supplied context (untrusted text)
    domain                TEXT,
    target_role           TEXT,
    file_count            INT,
    java_file_count       INT,
    total_lines           INT,
    chunk_count           INT,
    primary_language      VARCHAR(30),
    summary               JSONB,                          -- RepoSummary document
    repo_map              TEXT,                           -- token-budgeted outline used in prompts
    build_files           TEXT,                           -- for the SUMMARY prompt (was V4)
    readme                TEXT,
    analysis_started_at   TIMESTAMPTZ,
    analysis_completed_at TIMESTAMPTZ,
    version               BIGINT      NOT NULL DEFAULT 0, -- optimistic locking
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_url_commit UNIQUE (github_url, commit_sha),
    CONSTRAINT ck_project_status CHECK (status IN
        ('SUBMITTED', 'CLONING', 'PARSING', 'EMBEDDING', 'SUMMARIZING', 'READY', 'FAILED'))
);

CREATE INDEX ix_project_status ON project (status);
CREATE INDEX ix_project_created_at ON project (created_at DESC, id DESC); -- cursor pagination

-- ---------------------------------------------------------------------------
-- Files and chunks
-- ---------------------------------------------------------------------------
CREATE TABLE code_file (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    path        TEXT        NOT NULL,                     -- repo-relative, forward slashes
    language    VARCHAR(30) NOT NULL,                     -- java, yaml, properties, xml, sql, markdown, ...
    role        VARCHAR(20) NOT NULL,
    line_count  INT         NOT NULL,
    size_bytes  INT         NOT NULL,
    content_sha VARCHAR(64) NOT NULL,                     -- sha-256 of file content
    content     TEXT,                                     -- the whole redacted file, for CodeRef snippets (was V2)
    CONSTRAINT uq_code_file_path UNIQUE (project_id, path),
    CONSTRAINT ck_code_file_role CHECK (role IN
        ('CONTROLLER', 'SERVICE', 'REPOSITORY', 'ENTITY', 'DTO', 'CONFIG', 'SECURITY',
         'MESSAGING', 'TEST', 'MIGRATION', 'BUILD', 'DOC', 'OTHER'))
);

CREATE TABLE code_chunk (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id     UUID        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    file_id        UUID        NOT NULL REFERENCES code_file (id) ON DELETE CASCADE,
    path           TEXT        NOT NULL,                  -- denormalized for fast ref validation
    chunk_type     VARCHAR(20) NOT NULL,
    symbol         TEXT,                                  -- e.g. PaymentService#refund
    start_line     INT         NOT NULL,
    end_line       INT         NOT NULL,
    content        TEXT        NOT NULL,                  -- already secret-redacted
    token_estimate INT         NOT NULL,
    embedding      vector(768),                           -- nomic-embed-text; NULL until embedded
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_chunk_lines CHECK (start_line >= 1 AND end_line >= start_line),
    CONSTRAINT ck_chunk_type CHECK (chunk_type IN
        ('TYPE_HEADER', 'METHOD', 'CONFIG', 'DOC', 'SQL', 'TEXT'))
);

CREATE INDEX ix_chunk_project ON code_chunk (project_id);
CREATE INDEX ix_chunk_project_path ON code_chunk (project_id, path);
CREATE INDEX ix_chunk_project_symbol ON code_chunk (project_id, symbol);
-- No ANN index on purpose: retrieval always filters by project_id and a project has < 10K chunks, so an exact scan
-- is fast and has 100% recall (see the app's V1).

-- ---------------------------------------------------------------------------
-- Model calls of this service (SUMMARY): the gateway writes one row per attempt; the daily cap counts them.
-- session_id has no foreign key: sessions live in another service.
-- ---------------------------------------------------------------------------
CREATE TABLE llm_call (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purpose        VARCHAR(20) NOT NULL,
    model          VARCHAR(60) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,                    -- template name + short content hash
    project_id     UUID REFERENCES project (id) ON DELETE SET NULL,
    session_id     UUID,
    input_tokens   INT,
    output_tokens  INT,
    cached_tokens  INT,
    latency_ms     INT         NOT NULL,
    success        BOOLEAN     NOT NULL,
    error_type     VARCHAR(60),
    attempt        INT         NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_llm_purpose CHECK (purpose IN
        ('SUMMARY', 'PLAN', 'QUESTION', 'FOLLOW_UP', 'EVALUATION', 'REPORT'))
);

CREATE INDEX ix_llm_call_created ON llm_call (created_at);
CREATE INDEX ix_llm_call_session ON llm_call (session_id);

-- ---------------------------------------------------------------------------
-- Transactional outbox and idempotent consumers (were V3, task 2.2 and 2.3; LLD §13.1)
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_event (
    id             UUID PRIMARY KEY,                    -- UUIDv7 from Java; also the envelope's eventId
    aggregate_type VARCHAR(30) NOT NULL,                -- PROJECT | SESSION
    aggregate_id   UUID        NOT NULL,                -- also the Kafka key: one aggregate, one partition, in order
    topic          VARCHAR(80) NOT NULL,
    type           VARCHAR(60) NOT NULL,
    version        SMALLINT    NOT NULL DEFAULT 1,      -- payload schema version, sent in the envelope
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,                         -- NULL until the relay got Kafka's ack
    CONSTRAINT ck_outbox_aggregate_type CHECK (aggregate_type IN ('PROJECT', 'SESSION'))
);

CREATE INDEX ix_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_event (
    event_id     UUID        NOT NULL,
    consumer     VARCHAR(60) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
