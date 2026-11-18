-- The first launch (docs/design/23-first-launch.md; ROADMAP 17zz23): on a database that never had a user, the
-- platform applies the operator's first-launch configuration once. sys_first_launch_run holds that one run (a second
-- one is impossible: run_slot is always 1, so a second instance starting at the same moment loses the insert);
-- sys_first_launch_event what became of it. A run without an event is still running. Neither table holds an address
-- or a name: only the configuration's hash, its keys and user ids.
CREATE TABLE sys_first_launch_run (
    run_slot       smallint    PRIMARY KEY CHECK (run_slot = 1),
    run_id         uuid        NOT NULL UNIQUE,
    config_hash    text        NOT NULL,
    started_time   timestamptz NOT NULL,
    process_seq_id bigint      NOT NULL REFERENCES op_process (process_seq_id)
);
SELECT jabiz_protect_append_only('sys_first_launch_run');

-- CONTENT_DONE: the content phase is done and the application serves (the first launch's own powers end here for
-- good); DONE / FAILED / EXPIRED: the run has ended; REVISED: in the waiting phase, an unaccepted account's address
-- was corrected. The current state is the latest event of a terminal or phase kind (by process_seq_id).
CREATE TABLE sys_first_launch_event (
    event_id       uuid        PRIMARY KEY,
    run_id         uuid        NOT NULL REFERENCES sys_first_launch_run (run_id),
    kind           text        NOT NULL CHECK (kind IN ('CONTENT_DONE', 'DONE', 'FAILED', 'EXPIRED', 'REVISED')),
    step_key       text,
    reason_code    text,
    reason         text,
    config_hash    text        NOT NULL,
    detail         jsonb,
    event_time     timestamptz NOT NULL,
    process_seq_id bigint      NOT NULL REFERENCES op_process (process_seq_id)
);
CREATE INDEX sys_first_launch_event_run_idx ON sys_first_launch_event (run_id, process_seq_id);
CREATE INDEX sys_first_launch_event_process_idx ON sys_first_launch_event (process_seq_id);
SELECT jabiz_protect_append_only('sys_first_launch_event');
