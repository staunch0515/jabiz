-- Asynchronous external jobs (docs/design/11-ledger-events-jobs.md section 4a; decision D38 items 3-5). Every table is
-- append-only: a job's state is derived from the rows written about it, never updated in place. The executor takes due
-- jobs with SELECT ... FOR UPDATE OF job SKIP LOCKED, which locks rows without writing them (no UPDATE, so the
-- append-only guard is not involved), and every row it may race another instance for is inserted with
-- ON CONFLICT DO NOTHING on its primary key: the loser of a race inserts nothing and drops what it had.

-- One job, inserted by the step StartExternalJob in the transaction of the process that starts it: a rollback or a
-- dry run leaves no job. The input is JSON of the job type's input record, without secrets (the step refuses them).
CREATE TABLE sys_external_job (
    job_id          varchar(36)   PRIMARY KEY,
    job_seq         bigint        GENERATED ALWAYS AS IDENTITY UNIQUE,
    job_type        varchar(100)  NOT NULL,
    input           text          NOT NULL,
    reference       varchar(200),
    requested_by    varchar(100)  NOT NULL,
    process_seq_id  bigint        NOT NULL REFERENCES op_process (process_seq_id),
    created_time    timestamptz   NOT NULL
);
-- The executor looks for due jobs of one type in the order they came.
CREATE INDEX sys_external_job_type_idx ON sys_external_job (job_type, job_seq);
CREATE INDEX sys_external_job_requester_idx ON sys_external_job (requested_by, created_time);
-- StartExternalJob counts the open jobs of a type per requester and per reference (its start limits).
CREATE INDEX sys_external_job_type_requester_idx ON sys_external_job (job_type, requested_by);
CREATE INDEX sys_external_job_type_reference_idx ON sys_external_job (job_type, reference);
CREATE INDEX sys_external_job_process_idx ON sys_external_job (process_seq_id);
SELECT jabiz_protect_append_only('sys_external_job');

-- One attempt taken by one executor instance, held until lease_until (the attempt's timeout and a margin). A claim
-- without an attempt row is running while its lease lasts; an expired one is recorded as LEASE_EXPIRED by the next
-- claim of its type. The primary key is what guarantees that one attempt is run once.
CREATE TABLE sys_external_job_claim (
    job_id          varchar(36)   NOT NULL REFERENCES sys_external_job (job_id),
    attempt_no      integer       NOT NULL CHECK (attempt_no > 0),
    instance_id     varchar(100)  NOT NULL,
    claimed_time    timestamptz   NOT NULL,
    lease_until     timestamptz   NOT NULL,
    PRIMARY KEY (job_id, attempt_no)
);
-- Each claim looks for the leases that ran out.
CREATE INDEX sys_external_job_claim_lease_idx ON sys_external_job_claim (lease_until);
SELECT jabiz_protect_append_only('sys_external_job_claim');

-- How an attempt ended. A late instance whose lease expired finds its attempt recorded (LEASE_EXPIRED) and drops its
-- result. The error detail is the handler's own message (ExternalJobException) or the platform's, never an arbitrary
-- exception's, which may repeat a request or a secret. attempt_id identifies the row for the platform entity.
CREATE TABLE sys_external_job_attempt (
    job_id          varchar(36)   NOT NULL,
    attempt_no      integer       NOT NULL,
    attempt_id      varchar(36)   NOT NULL UNIQUE,
    outcome         varchar(20)   NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'LEASE_EXPIRED')),
    retryable       boolean       NOT NULL,
    error_code      varchar(100),
    error_detail    varchar(2000),
    duration_ms     bigint,
    finished_time   timestamptz   NOT NULL,
    PRIMARY KEY (job_id, attempt_no),
    FOREIGN KEY (job_id, attempt_no) REFERENCES sys_external_job_claim (job_id, attempt_no)
);
-- Usage reports count attempts by the month they ended.
CREATE INDEX sys_external_job_attempt_finished_idx ON sys_external_job_attempt (finished_time);
SELECT jabiz_protect_append_only('sys_external_job_attempt');

-- What an attempt consumed, by metric (tokens of a model, say), failed attempts included; recorded with the attempt.
CREATE TABLE sys_external_job_usage (
    job_id          varchar(36)   NOT NULL,
    attempt_no      integer       NOT NULL,
    metric          varchar(64)   NOT NULL,
    usage_id        varchar(36)   NOT NULL UNIQUE,
    quantity        bigint        NOT NULL CHECK (quantity >= 0),
    PRIMARY KEY (job_id, attempt_no, metric),
    FOREIGN KEY (job_id, attempt_no) REFERENCES sys_external_job_attempt (job_id, attempt_no)
);
SELECT jabiz_protect_append_only('sys_external_job_usage');

-- A cancellation by the requester or a holder of external-job.cancel (EXTERNAL_JOB_CANCEL). A job not running gets its
-- outcome CANCELLED at once; a running one when its attempt ends, whose result is dropped.
CREATE TABLE sys_external_job_cancel (
    job_id          varchar(36)   PRIMARY KEY REFERENCES sys_external_job (job_id),
    cancelled_by    varchar(100)  NOT NULL,
    cancelled_time  timestamptz   NOT NULL,
    process_seq_id  bigint        NOT NULL REFERENCES op_process (process_seq_id)
);
CREATE INDEX sys_external_job_cancel_process_idx ON sys_external_job_cancel (process_seq_id);
SELECT jabiz_protect_append_only('sys_external_job_cancel');

-- The one final outcome of a job, written together with the outbox event jabiz.external-job.finished that leads to its
-- completion or failure process. The result is JSON of the job type's result record, at most 256 KiB.
CREATE TABLE sys_external_job_outcome (
    job_id          varchar(36)   PRIMARY KEY REFERENCES sys_external_job (job_id),
    status          varchar(20)   NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')),
    result          text,
    error_code      varchar(100),
    error_detail    varchar(2000),
    attempts        integer       NOT NULL,
    finished_time   timestamptz   NOT NULL
);
SELECT jabiz_protect_append_only('sys_external_job_outcome');
