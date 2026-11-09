-- F-C10 (docs/quizbuks/plans/impl/F-C10.md): the AI help's conversations, messages and usage. The number is temporary:
-- renumbered to the highest on 1.3/quizbuks + 1 before merging (one file).
--
-- Ordinary tables, never append-only: an erased account's conversations are deleted (platform decision D62), and the
-- retention deletes old ones. Written by the QB_HELP_* processes only (every change audited by the platform); ids only,
-- no foreign keys (as the other help-like records: the exposures, the conversations of Q82).

-- A Taker's ("app") or a Sponsor's ("sponsor") conversation with the AI help, and with a person once handed over: the
-- operator it went to (none: headquarters), whom it waits for and since when (the reminder and the closing by itself).
CREATE TABLE qb_help_thread (
    thread_id        varchar(36)  PRIMARY KEY,
    user_id          varchar(128) NOT NULL,
    entry            varchar(10)  NOT NULL CHECK (entry IN ('app', 'sponsor')),
    language         varchar(5)   NOT NULL,
    start_page       varchar(40),
    status           varchar(10)  NOT NULL CHECK (status IN ('OPEN', 'ESCALATED', 'CLOSED')),
    escalated        boolean      NOT NULL,
    escalated_at     timestamptz,
    operator_id      varchar(36),
    waiting_for      varchar(10)  CHECK (waiting_for IN ('STAFF', 'USER')),
    waiting_since    timestamptz,
    resolved         boolean,
    closed_at        timestamptz,
    close_reason     varchar(10)  CHECK (close_reason IN ('USER', 'STAFF', 'RESOLVED', 'AUTO')),
    created_at       timestamptz  NOT NULL,
    last_activity_at timestamptz  NOT NULL,
    version          bigint       NOT NULL,
    CHECK (status <> 'ESCALATED' OR (escalated AND escalated_at IS NOT NULL))
);
-- The user's own conversations, newest first (their history; the erasure).
CREATE INDEX qb_help_thread_user_idx ON qb_help_thread (user_id, last_activity_at DESC);
-- The retention's daily selection.
CREATE INDEX qb_help_thread_activity_idx ON qb_help_thread (last_activity_at);
-- The conversations handed to a person and still open: the staff's list, the reminders and the closing by itself.
CREATE INDEX qb_help_thread_waiting_idx ON qb_help_thread (waiting_for, waiting_since) WHERE status = 'ESCALATED';

-- A conversation's messages: the questions to the AI (with their job's state and what an attempt reserved), the AI's
-- answers (same-site links only), what the user wrote to the person and the person's replies; the user of the
-- conversation on each (the operators' scope, the erasure); an answer's or reply's rating.
CREATE TABLE qb_help_message (
    message_id            varchar(36)    PRIMARY KEY,
    thread_id             varchar(36)    NOT NULL,
    user_id               varchar(128)   NOT NULL,
    kind                  varchar(10)    NOT NULL CHECK (kind IN ('QUESTION', 'ANSWER', 'USER', 'STAFF')),
    body                  varchar(8000)  NOT NULL,
    links                 varchar(2000),
    page                  varchar(40),
    job_status            varchar(10)    CHECK (job_status IN ('QUEUED', 'ANSWERED', 'FAILED', 'CANCELLED')),
    failure_code          varchar(100),
    model                 varchar(60),
    reserved_input_tokens numeric(12, 0) CHECK (reserved_input_tokens >= 0),
    rating                varchar(10)    CHECK (rating IN ('SOLVED', 'UNSOLVED')),
    rated_at              timestamptz,
    created_at            timestamptz    NOT NULL,
    version               bigint         NOT NULL
);
-- A conversation's messages in order (its page, the latest turns sent with a question, the retention).
CREATE INDEX qb_help_message_thread_idx ON qb_help_message (thread_id, created_at);
-- A person's questions of the day (the daily limit) and the erasure.
CREATE INDEX qb_help_message_user_idx ON qb_help_message (user_id, kind, created_at);
-- The month's unsolved answers and replies (qb.admin.help-gaps).
CREATE INDEX qb_help_message_rating_idx ON qb_help_message (created_at) WHERE rating = 'UNSOLVED';

-- What the help's jobs used, per user and month (the deployment's zone), and for everybody (the user "*"): its own,
-- never a Sponsor's AI usage, fees or limits (A1-9). The tokens include each attempt that got no answer at its estimate.
CREATE TABLE qb_help_usage (
    usage_id       varchar(36)    PRIMARY KEY,
    user_id        varchar(128)   NOT NULL,
    month          varchar(7)     NOT NULL CHECK (month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    questions      numeric(9, 0)  NOT NULL CHECK (questions >= 0),
    failed_jobs    numeric(9, 0)  NOT NULL CHECK (failed_jobs >= 0),
    cancelled_jobs numeric(9, 0)  NOT NULL CHECK (cancelled_jobs >= 0),
    input_tokens   numeric(15, 0) NOT NULL CHECK (input_tokens >= 0),
    output_tokens  numeric(15, 0) NOT NULL CHECK (output_tokens >= 0),
    cost_usd       numeric(16, 6) CHECK (cost_usd >= 0),
    version        bigint         NOT NULL,
    CONSTRAINT uk_qb_help_usage_month UNIQUE (user_id, month)
);
