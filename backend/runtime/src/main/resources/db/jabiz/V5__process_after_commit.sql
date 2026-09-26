-- Attempts of process steps that run after their operation committed (docs/design/06-process.md section 4;
-- decision D11). One row per attempt, never updated: the log is append-only like the other operation tables (D4).
CREATE TABLE op_process_after_commit (
    process_seq_id bigint      NOT NULL REFERENCES op_process,
    step_name      text        NOT NULL,
    attempt        integer     NOT NULL,
    succeeded      boolean     NOT NULL,
    error          text,                                 -- exception type and message of a failed attempt
    attempted_at   timestamptz NOT NULL,                 -- from the application clock
    PRIMARY KEY (process_seq_id, step_name, attempt)
);

SELECT jabiz_protect_append_only('op_process_after_commit');
