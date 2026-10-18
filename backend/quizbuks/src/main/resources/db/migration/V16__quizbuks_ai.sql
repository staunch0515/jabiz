-- AI generation of questions (docs/quizbuks/plans/Q8-ai.md, Q8a, D-Q8-3). Both tables are ordinary, written only by
-- the processes of content.ai.AiProcesses: a job's state changes within minutes and it is found by the platform's job
-- id (as a top-up by Stripe's session); the platform's audit keeps the history. Generated keys are text (UUIDv7).
-- Migration numbers (2026-10-18): Q7b V12, Q7a V13, Q9e1 V14, Q7c V15, Q8a V16.

-- One request for candidates. The candidates are kept only while they wait for the sponsor's review (READY); nothing
-- reaches the quiz without it (QB_AI_ACCEPT). The tokens are the provider's usage of every attempt.
CREATE TABLE qb_ai_job (
    ai_job_id        varchar(36)    PRIMARY KEY,
    external_job_id  varchar(36)    NOT NULL,
    sponsor_id       varchar(128)   NOT NULL,
    quiz_id          uuid           NOT NULL,
    kind             varchar(20)    NOT NULL CHECK (kind IN ('QUESTIONS', 'COVER')),
    params           varchar(2000)  NOT NULL,
    model            varchar(100)   NOT NULL,
    served_model     varchar(100),
    status           varchar(20)    NOT NULL CHECK (status IN ('QUEUED', 'READY', 'FAILED', 'CANCELLED', 'ACCEPTED',
                                                               'DISCARDED', 'EXPIRED')),
    candidates       text,
    candidate_count  numeric(4, 0)  CHECK (candidate_count >= 0),
    invalid_count    numeric(4, 0)  CHECK (invalid_count >= 0),
    accepted_count   numeric(4, 0)  CHECK (accepted_count >= 0),
    input_bytes      numeric(9, 0)  NOT NULL CHECK (input_bytes >= 0),
    estimated_tokens numeric(12, 0) NOT NULL CHECK (estimated_tokens >= 0),
    input_tokens     numeric(12, 0) NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens    numeric(12, 0) NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    fallback_input_tokens  numeric(12, 0) CHECK (fallback_input_tokens >= 0),
    fallback_output_tokens numeric(12, 0) CHECK (fallback_output_tokens >= 0),
    cost_usd         numeric(14, 6) CHECK (cost_usd >= 0),
    zone             varchar(64)    NOT NULL,
    attempts         numeric(4, 0),
    failure_code     varchar(100),
    failure_message  varchar(500),
    requested_at     timestamptz    NOT NULL,
    finished_at      timestamptz,
    expires_at       timestamptz,
    closed_at        timestamptz,
    version          bigint         NOT NULL,
    CONSTRAINT uk_qb_ai_job_external UNIQUE (external_job_id),
    -- Candidates only while they wait for review; a ready job always has its expiry.
    CONSTRAINT qb_ai_job_candidates CHECK (candidates IS NULL OR status = 'READY'),
    CONSTRAINT qb_ai_job_ready CHECK (status <> 'READY' OR (candidates IS NOT NULL AND expires_at IS NOT NULL))
);
-- A sponsor's jobs, newest first (the limits: the day's jobs and the open ones); a quiz's (the editor's panel); the
-- daily expiry of the ready ones; everybody's unfinished ones.
CREATE INDEX qb_ai_job_sponsor_idx ON qb_ai_job (sponsor_id, requested_at DESC);
CREATE INDEX qb_ai_job_quiz_idx ON qb_ai_job (quiz_id, requested_at DESC);
CREATE INDEX qb_ai_job_ready_idx ON qb_ai_job (expires_at) WHERE status = 'READY';
-- The unfinished jobs of everybody (the global limit adds what they may still use, under qb.ai:*): few, and only these.
CREATE INDEX qb_ai_job_open_idx ON qb_ai_job (sponsor_id) INCLUDE (estimated_tokens) WHERE finished_at IS NULL;

-- What a sponsor's jobs used in a month of the deployment's zone (YYYY-MM), and one row per month for everybody
-- (sponsor_id = '*'). Added to under the locks qb.ai:<sponsor> and qb.ai:* only.
CREATE TABLE qb_ai_usage (
    usage_id      varchar(36)    PRIMARY KEY,
    sponsor_id    varchar(128)   NOT NULL,
    month         varchar(7)     NOT NULL CHECK (month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    jobs          numeric(9, 0)  NOT NULL CHECK (jobs >= 0),
    failed_jobs   numeric(9, 0)  NOT NULL CHECK (failed_jobs >= 0 AND failed_jobs <= jobs),
    input_tokens  numeric(15, 0) NOT NULL CHECK (input_tokens >= 0),
    output_tokens numeric(15, 0) NOT NULL CHECK (output_tokens >= 0),
    cost_usd      numeric(16, 6) CHECK (cost_usd >= 0),
    version       bigint         NOT NULL,
    CONSTRAINT uk_qb_ai_usage_month UNIQUE (sponsor_id, month)
);
-- The administrators' page reads a month across sponsors.
CREATE INDEX qb_ai_usage_month_idx ON qb_ai_usage (month);

-- The job a question was accepted from (M-20): a column added to a temporal table, which stays insert-only.
ALTER TABLE qb_question_version ADD COLUMN ai_job_id varchar(36);
