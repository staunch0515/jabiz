-- Imports (docs/design/20-imports.md section 5, decision D26): every committed import and every rejected attempt, with
-- what it read (file hash, mapping, parameters), what it found (counts, control totals, problems) and the notes of the
-- person who committed it. A committed file is imported once per import: the partial unique index refuses a second
-- commit of the same content, also when two arrive at once. Append-only (decision D5).
CREATE TABLE sys_import_run (
    run_id          uuid          PRIMARY KEY,
    import_id       varchar(100)  NOT NULL,
    import_version  integer       NOT NULL,
    outcome         varchar(10)   NOT NULL CHECK (outcome IN ('committed', 'rejected')),
    file_id         uuid          NOT NULL,
    file_sha256     char(64)      NOT NULL,
    mapping         text          NOT NULL,
    params          text          NOT NULL,
    record_count    integer       NOT NULL CHECK (record_count >= 0),
    row_count       integer       NOT NULL CHECK (row_count >= 0),
    unit_count      integer       NOT NULL CHECK (unit_count >= 0),
    processed_count integer       NOT NULL CHECK (processed_count >= 0),
    duplicate_count integer       NOT NULL CHECK (duplicate_count >= 0),
    issue_count     integer       NOT NULL CHECK (issue_count >= 0),
    columns         text          NOT NULL,
    totals          text          NOT NULL,
    issues          text          NOT NULL,
    notes           varchar(4000),
    imported_by     varchar(100)  NOT NULL,
    imported_time   timestamptz   NOT NULL,
    process_seq_id  bigint        NOT NULL REFERENCES op_process (process_seq_id)
);
CREATE UNIQUE INDEX sys_import_run_file_uk ON sys_import_run (import_id, file_sha256) WHERE outcome = 'committed';
CREATE INDEX sys_import_run_import_idx ON sys_import_run (import_id, imported_time DESC);
CREATE INDEX sys_import_run_process_idx ON sys_import_run (process_seq_id);
SELECT jabiz_protect_append_only('sys_import_run');

-- External references imported (a bank statement line, an invoice number of the source system): each once per import,
-- written in the transaction of the rows themselves, so a row and its reference stand or fall together.
CREATE TABLE sys_import_ref (
    import_id      varchar(100) NOT NULL,
    ref            varchar(500) NOT NULL,
    run_id         uuid         NOT NULL REFERENCES sys_import_run (run_id),
    row_number     integer      NOT NULL,
    process_seq_id bigint       NOT NULL REFERENCES op_process (process_seq_id),
    PRIMARY KEY (import_id, ref)
);
CREATE INDEX sys_import_ref_run_idx ON sys_import_ref (run_id);
CREATE INDEX sys_import_ref_process_idx ON sys_import_ref (process_seq_id);
SELECT jabiz_protect_append_only('sys_import_ref');
