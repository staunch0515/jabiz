-- Scheduled jobs (docs/design/11-ledger-events-jobs.md section 4; decision D14).

-- ShedLock's cluster lock (https://github.com/lukas-krecan/ShedLock): one row per job, updated in place by ShedLock.
-- Infrastructure, not business data, hence not append-only. Named for the platform so that an application's own
-- ShedLock table (usually "shedlock") does not collide with it.
CREATE TABLE jabiz_shedlock (
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL
);

-- One row per run an instance performed (it held the lock): append-only (decision D5).
CREATE TABLE sys_job_run (
    run_id         uuid         PRIMARY KEY,
    job_name       varchar(80)  NOT NULL,
    scheduled_time timestamptz  NOT NULL,
    instance_id    varchar(255) NOT NULL,
    outcome        varchar(20)  NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'REPLAYED')),
    process_seq_id bigint       REFERENCES op_process (process_seq_id),
    error          text,
    started_time   timestamptz  NOT NULL,
    finished_time  timestamptz  NOT NULL
);
CREATE INDEX sys_job_run_job_idx ON sys_job_run (job_name, scheduled_time DESC);
SELECT jabiz_protect_append_only('sys_job_run');
