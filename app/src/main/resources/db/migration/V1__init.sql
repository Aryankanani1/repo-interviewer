-- V1: initial schema for Repo Interviewer (Phase 1).
-- Rule: never edit this file after it has been applied anywhere. Add V2__..., V3__... instead.

CREATE EXTENSION IF NOT EXISTS vector;

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
-- No ANN index on purpose: retrieval always filters by project_id and a project has
-- < 10K chunks, so an exact scan is fast and has 100% recall. If that changes, add:
-- CREATE INDEX ix_chunk_embedding_hnsw ON code_chunk USING hnsw (embedding vector_cosine_ops);
-- (and consider SET hnsw.iterative_scan = relaxed_order for filtered queries)

-- ---------------------------------------------------------------------------
-- Interview sessions
-- ---------------------------------------------------------------------------
CREATE TABLE interview_session (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id             UUID        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    status                 VARCHAR(20) NOT NULL,
    difficulty             VARCHAR(10) NOT NULL,
    focus_areas            JSONB       NOT NULL DEFAULT '[]'::jsonb,  -- list of QuestionCategory
    planned_question_count INT         NOT NULL,
    current_question_id    UUID,                                       -- FK added after question table
    overall_score          NUMERIC(4, 2),
    report                 JSONB,                                      -- InterviewReport document
    failure_reason         TEXT,
    llm_call_count         INT         NOT NULL DEFAULT 0,
    version                BIGINT      NOT NULL DEFAULT 0,
    started_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_session_status CHECK (status IN
        ('PLANNING', 'IN_PROGRESS', 'COMPLETED', 'ABANDONED', 'FAILED')),
    CONSTRAINT ck_session_difficulty CHECK (difficulty IN ('INTERN', 'JUNIOR', 'MID')),
    CONSTRAINT ck_session_count CHECK (planned_question_count BETWEEN 1 AND 8)
);

CREATE INDEX ix_session_project ON interview_session (project_id, started_at DESC);

CREATE TABLE interview_topic (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID        NOT NULL REFERENCES interview_session (id) ON DELETE CASCADE,
    order_index     INT         NOT NULL,
    category        VARCHAR(20) NOT NULL,
    title           TEXT        NOT NULL,
    rationale       TEXT,
    target_paths    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    target_symbols  JSONB       NOT NULL DEFAULT '[]'::jsonb,
    status          VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    follow_up_count INT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_topic_order UNIQUE (session_id, order_index),
    CONSTRAINT ck_topic_status CHECK (status IN ('PENDING', 'ASKED', 'DONE', 'SKIPPED'))
);

CREATE TABLE question (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id           UUID        NOT NULL REFERENCES interview_session (id) ON DELETE CASCADE,
    topic_id             UUID        NOT NULL REFERENCES interview_topic (id) ON DELETE CASCADE,
    parent_question_id   UUID        REFERENCES question (id) ON DELETE CASCADE,
    order_index          INT,                               -- assigned when ASKED; NULL while PREPARED
    category             VARCHAR(20) NOT NULL,
    text                 TEXT        NOT NULL,
    is_follow_up         BOOLEAN     NOT NULL DEFAULT FALSE,
    code_refs            JSONB       NOT NULL DEFAULT '[]'::jsonb, -- [{path,startLine,endLine}]
    rubric               JSONB       NOT NULL,                     -- hidden until answered
    ideal_answer_outline JSONB       NOT NULL DEFAULT '[]'::jsonb, -- hidden until answered
    status               VARCHAR(10) NOT NULL,
    prompt_version       VARCHAR(40),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    asked_at             TIMESTAMPTZ,
    CONSTRAINT uq_question_order UNIQUE (session_id, order_index),
    CONSTRAINT ck_question_status CHECK (status IN ('PREPARED', 'ASKED', 'ANSWERED', 'SKIPPED')),
    CONSTRAINT ck_follow_up_parent CHECK (is_follow_up = (parent_question_id IS NOT NULL))
);

-- At most one main (non-follow-up) question per topic. Protects against two
-- concurrent prefetch/generation attempts creating duplicates.
CREATE UNIQUE INDEX uq_question_main_per_topic ON question (topic_id) WHERE is_follow_up = FALSE;
CREATE INDEX ix_question_session ON question (session_id);

ALTER TABLE interview_session
    ADD CONSTRAINT fk_session_current_question
    FOREIGN KEY (current_question_id) REFERENCES question (id) ON DELETE SET NULL;

CREATE TABLE answer (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id       UUID        NOT NULL REFERENCES question (id) ON DELETE CASCADE,
    text              TEXT        NOT NULL,                -- untrusted
    text_sha          VARCHAR(64) NOT NULL,                -- for idempotent re-submits
    -- evaluation fields: NULL until evaluated (Phase 2 evaluates asynchronously)
    score             SMALLINT,
    verdict           VARCHAR(10),
    feedback          TEXT,
    covered_points    JSONB,
    missed_points     JSONB,
    incorrect_claims  JSONB,
    follow_up_needed  BOOLEAN,
    follow_up_reason  TEXT,
    submitted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    evaluated_at      TIMESTAMPTZ,
    CONSTRAINT uq_answer_question UNIQUE (question_id),
    CONSTRAINT ck_answer_score CHECK (score IS NULL OR score BETWEEN 0 AND 10),
    CONSTRAINT ck_answer_verdict CHECK (verdict IS NULL OR verdict IN ('STRONG', 'ADEQUATE', 'WEAK', 'OFF_TOPIC'))
);

-- ---------------------------------------------------------------------------
-- LLM call ledger: every model call writes exactly one row
-- ---------------------------------------------------------------------------
CREATE TABLE llm_call (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purpose        VARCHAR(20) NOT NULL,
    model          VARCHAR(60) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,                    -- template name + short content hash
    project_id     UUID REFERENCES project (id) ON DELETE SET NULL,
    session_id     UUID REFERENCES interview_session (id) ON DELETE SET NULL,
    input_tokens   INT,
    output_tokens  INT,
    cached_tokens  INT,                                     -- filled once prompt caching is on (Phase 4)
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
