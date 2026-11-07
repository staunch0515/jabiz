-- Runs of a past scheduled time an administrator asked for (decision D77; docs/design/11-ledger-events-jobs.md
-- section 4.2): who asked and the operation of the request (JOB_RUN_REQUEST, which carries the reason); both null for
-- the runs the scheduler started. Columns are only added (decision D58): no UPDATE runs, and the rows sealed before
-- still verify over the columns their blocks were digested with.
ALTER TABLE sys_job_run ADD COLUMN requested_by   varchar(100);
ALTER TABLE sys_job_run ADD COLUMN request_seq_id bigint REFERENCES op_process (process_seq_id);
