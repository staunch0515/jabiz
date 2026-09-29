-- Gap-free document numbers (docs/design/18-numbering-approvals-tasks.md section 2; decision D23).

-- The last number drawn per sequence and scope. Mutable infrastructure, like jabiz_shedlock: the step AssignNumber
-- increments a row inside the process's transaction and holds its lock until the transaction ends, so a rolled-back
-- process gives its number back and concurrent processes of the same scope draw one after another.
CREATE TABLE sys_number_counter (
    sequence_name varchar(100) NOT NULL,
    scope_key     varchar(100) NOT NULL,
    last_value    bigint       NOT NULL CHECK (last_value >= 1),
    PRIMARY KEY (sequence_name, scope_key)
);

-- Every number issued, once: the platform entity NumberAssignment (read-only through its dataset). Append-only.
CREATE TABLE sys_number_assignment (
    assignment_id  uuid           PRIMARY KEY,
    sequence_name  varchar(100)   NOT NULL,
    scope_key      varchar(100)   NOT NULL,
    value_no       numeric(19, 0) NOT NULL CHECK (value_no >= 1),
    number         varchar(200)   NOT NULL,
    process_seq_id bigint         NOT NULL REFERENCES op_process (process_seq_id),
    assigned_time  timestamptz    NOT NULL,
    version        bigint         NOT NULL,
    CONSTRAINT sys_number_assignment_value_key UNIQUE (sequence_name, scope_key, value_no)
);
CREATE INDEX sys_number_assignment_process_idx ON sys_number_assignment (process_seq_id);
SELECT jabiz_protect_append_only('sys_number_assignment');
