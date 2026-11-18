-- The first launch's accounts (docs/design/23-first-launch.md section 8; ROADMAP 17zz23c): one row per account of
-- the configuration, written in the content phase by the step that creates it, read by the waiting phase's job
-- jabiz.first-launch. Only the user id, the account's place in the configuration and the configured role codes: never
-- an address, a name or the account's key, which is its user name (those live on the user and are erased with it).
-- Append-only.
CREATE TABLE sys_first_launch_account (
    run_id         uuid         NOT NULL REFERENCES sys_first_launch_run (run_id),
    user_id        uuid         NOT NULL UNIQUE,
    position       integer      NOT NULL,
    roles          text         NOT NULL,
    report         boolean      NOT NULL,
    process_seq_id bigint       NOT NULL REFERENCES op_process (process_seq_id),
    PRIMARY KEY (run_id, user_id)
);
CREATE INDEX sys_first_launch_account_process_idx ON sys_first_launch_account (process_seq_id);
SELECT jabiz_protect_append_only('sys_first_launch_account');
