-- Issued reports (docs/design/19-reports.md section 5, decision D25): everything needed to show an issued report again
-- exactly - its template as it was, the parameters and point in time, the page header, the columns and the rows as
-- canonical JSON (text, not jsonb: jsonb would reorder keys and drop the exact form the content hash is computed on) -
-- and the hash itself. as_of is the effective time asked for (shown in the page header), read_at the one the
-- temporal entities were actually read at (the issue time when none was asked for), which verification reuses. Append-only: an issued report is never changed; a correction is a new run that supersedes it.
CREATE TABLE sys_report_run (
    run_id           uuid          PRIMARY KEY,
    template_id      varchar(200)  NOT NULL,
    template_version char(64)      NOT NULL,
    template_source  text          NOT NULL,
    permissions      varchar(2000) NOT NULL,
    title            varchar(500)  NOT NULL,
    company          varchar(500)  NOT NULL,
    period           varchar(500),
    language         varchar(10)   NOT NULL,
    params           text          NOT NULL,
    parameters       text          NOT NULL,
    as_of            timestamptz,
    read_at          timestamptz   NOT NULL,
    known_at         timestamptz   NOT NULL,
    landscape        boolean       NOT NULL,
    columns          text          NOT NULL,
    rows             text          NOT NULL,
    row_count        integer       NOT NULL CHECK (row_count >= 0),
    content_hash     char(64)      NOT NULL,
    recomputable     boolean       NOT NULL,
    issued_by        varchar(100)  NOT NULL,
    issued_time      timestamptz   NOT NULL,
    process_seq_id   bigint        NOT NULL REFERENCES op_process (process_seq_id),
    version          bigint        NOT NULL
);
CREATE INDEX sys_report_run_template_idx ON sys_report_run (template_id, issued_time DESC);
CREATE INDEX sys_report_run_process_idx ON sys_report_run (process_seq_id);
SELECT jabiz_protect_append_only('sys_report_run');

-- A run superseded by a later one (a corrected close, a reissued statement); a run is superseded at most once.
CREATE TABLE sys_report_run_supersede (
    run_id          uuid        PRIMARY KEY REFERENCES sys_report_run (run_id),
    superseded_by   uuid        NOT NULL REFERENCES sys_report_run (run_id),
    superseded_time timestamptz NOT NULL,
    process_seq_id  bigint      NOT NULL REFERENCES op_process (process_seq_id),
    CHECK (run_id <> superseded_by)
);
CREATE INDEX sys_report_run_supersede_by_idx ON sys_report_run_supersede (superseded_by);
CREATE INDEX sys_report_run_supersede_process_idx ON sys_report_run_supersede (process_seq_id);
SELECT jabiz_protect_append_only('sys_report_run_supersede');
