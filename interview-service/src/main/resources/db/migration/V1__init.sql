-- V1 of interview-service (task 4.1 step 3b): its tables in its own schema "interview", owned by interview_svc.
-- Rule: never edit this file after it has been applied anywhere. Add V2__..., V3__... instead.
-- The interview tables the app's V1, V3 and V5 made in "public" (interview_session, interview_topic, question,
-- answer, llm_call, outbox_event, processed_event), in one file; project ids have no foreign key, since projects live
-- in analysis-service (as after the app's V5). Those old "public" tables are left as they were: their data is not
-- copied here (D-026). Needs db/bootstrap.sql first (the schema).

-- ---------------------------------------------------------------------------
-- Interview sessions
-- ---------------------------------------------------------------------------
CREATE TABLE interview_session (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id             UUID        NOT NULL,                       -- analysis-service's, checked via AnalysisApi
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
    project_id     UUID,                                    -- analysis-service's: no foreign key across schemas
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

-- Serves the relay's poll: unpublished rows, oldest first. Partial, so it holds only rows still to send and stays
-- small however many events were published.
CREATE INDEX ix_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

-- Consumers insert (event id, consumer name) in the same transaction as their state change; a duplicate key means
-- the event was already handled (task 2.3).
CREATE TABLE processed_event (
    event_id     UUID        NOT NULL,
    consumer     VARCHAR(60) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
