-- The steps the application runs for a first-launch account once it is ready in the waiting phase
-- (FirstLaunchHandler.whenAccountReady; docs/design/23-first-launch.md section 9): one row per step that ran, written
-- in the same transaction as the step, so that each runs once. The user id and the step's key only. Append-only.
CREATE TABLE sys_first_launch_ready (
    run_id         uuid         NOT NULL REFERENCES sys_first_launch_run (run_id),
    user_id        uuid         NOT NULL,
    step_key       varchar(200) NOT NULL,
    process_seq_id bigint       NOT NULL REFERENCES op_process (process_seq_id),
    PRIMARY KEY (run_id, user_id, step_key)
);
CREATE INDEX sys_first_launch_ready_process_idx ON sys_first_launch_ready (process_seq_id);
SELECT jabiz_protect_append_only('sys_first_launch_ready');
