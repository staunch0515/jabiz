-- Per-item jobs (docs/design/11-ledger-events-jobs.md section 4.1; decision D42). Numbered V36: V35 is taken by the
-- parallel phase 16e-2 (whichever merges first keeps its number, ROADMAP 16k).

-- A run's counts of items by result; null for single-process jobs. Adding columns runs no UPDATE, so the append-only
-- guard of the table is not involved, and the integrity seals keep the columns each block was digested over
-- (docs/design/21-audit-retention.md section 2): rows sealed before this migration still verify.
ALTER TABLE sys_job_run ADD COLUMN items_selected  integer;
ALTER TABLE sys_job_run ADD COLUMN items_succeeded integer;
ALTER TABLE sys_job_run ADD COLUMN items_replayed  integer;
ALTER TABLE sys_job_run ADD COLUMN items_busy      integer;
ALTER TABLE sys_job_run ADD COLUMN items_failed    integer;
ALTER TABLE sys_job_run ADD COLUMN items_deferred  integer;
ALTER TABLE sys_job_run ADD COLUMN items_more      boolean;
ALTER TABLE sys_job_run DROP CONSTRAINT sys_job_run_outcome_check;
ALTER TABLE sys_job_run ADD CONSTRAINT sys_job_run_outcome_check
    CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'REPLAYED', 'PARTIAL'));

-- Failures of items, and their recovery, one row each as they happen: append-only (decision D5). An item's consecutive
-- failures are the FAILED rows after its latest RECOVERED row; they set its backoff. A row's attempt carries the count,
-- so an item's state is its latest row, in the order of entry_no (recorded_time comes from the injected clock and may
-- repeat). No foreign key to sys_job_run: the run's row is written when the run ends, the failures while it runs.
CREATE TABLE sys_job_item_failure (
    failure_id     uuid         PRIMARY KEY,
    entry_no       bigint       GENERATED ALWAYS AS IDENTITY UNIQUE,
    job_name       varchar(80)  NOT NULL,
    item_key       varchar(36)  NOT NULL,
    kind           varchar(10)  NOT NULL CHECK (kind IN ('FAILED', 'RECOVERED')),
    run_id         uuid         NOT NULL,
    scheduled_time timestamptz  NOT NULL,
    attempt        integer      NOT NULL,   -- the how-manieth consecutive failure (0 for RECOVERED)
    error          text,                    -- at most 2 000 characters, as in sys_job_run
    process_seq_id bigint       REFERENCES op_process (process_seq_id),  -- the successful operation of RECOVERED
    recorded_time  timestamptz  NOT NULL    -- from the injected clock
);
CREATE INDEX sys_job_item_failure_key_idx ON sys_job_item_failure (job_name, item_key, entry_no DESC);
-- The items in backoff are read from the last stretch of a job's rows only (at most its longest retry delay).
CREATE INDEX sys_job_item_failure_time_idx ON sys_job_item_failure (job_name, recorded_time);
SELECT jabiz_protect_append_only('sys_job_item_failure');
