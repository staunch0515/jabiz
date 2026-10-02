-- The period close (docs/finance/00-design.md section 7.3; ROADMAP F8a; FIN-PC-004, PC-005, CT-005): the checklist's
-- template, each period's tasks and the artifacts closes leave, written once with their lines.

CREATE FUNCTION finance_create_temporal_table(p_table text, p_key text, p_columns text) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('CREATE TABLE %I (
        row_id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        %I                uuid        NOT NULL REFERENCES entity_registry (entity_id),
        version_no        integer     NOT NULL,
        effect_start_time timestamptz NOT NULL,
        created_time      timestamptz NOT NULL,
        process_seq_id    bigint      NOT NULL REFERENCES op_process (process_seq_id),
        is_deleted        boolean     NOT NULL DEFAULT false,
        %s,
        CONSTRAINT %I UNIQUE (%I, version_no))',
        p_table, p_key, p_columns, p_table || '_uk', p_key);
    EXECUTE format('CREATE INDEX %I ON %I (%I, effect_start_time DESC, version_no DESC)',
        p_table || '_current_idx', p_table, p_key);
    EXECUTE format('CREATE INDEX %I ON %I (process_seq_id)', p_table || '_process_idx', p_table);
    PERFORM jabiz_protect_append_only(p_table::regclass);
END $$;

SELECT finance_create_temporal_table('fi_close_template_version', 'template_id', '
    task_code        varchar(30)   NOT NULL,
    name             varchar(200)  NOT NULL,
    kind             varchar(10)   NOT NULL,
    check_code       varchar(30),
    owner_permission varchar(100),
    due_days         numeric(3,0)  NOT NULL,
    required         boolean       NOT NULL,
    sort_order       numeric(4,0)  NOT NULL,
    active           boolean       NOT NULL');
CREATE INDEX fi_close_template_version_code_idx ON fi_close_template_version (task_code);

SELECT finance_create_temporal_table('fi_close_task_version', 'task_id', '
    period_key       varchar(7)    NOT NULL,
    task_code        varchar(30)   NOT NULL,
    name             varchar(200)  NOT NULL,
    kind             varchar(10)   NOT NULL,
    check_code       varchar(30),
    owner_permission varchar(100),
    due_date         date          NOT NULL,
    required         boolean       NOT NULL,
    sort_order       numeric(4,0)  NOT NULL,
    status           varchar(10)   NOT NULL,
    result           varchar(2000),
    evidence         varchar(500),
    checked_at       timestamptz,
    completed_by     varchar(100),
    completed_at     timestamptz,
    note             varchar(1000),
    evidence_file_id uuid');
CREATE INDEX fi_close_task_version_period_idx ON fi_close_task_version (period_key, task_code);

SELECT finance_create_temporal_table('fi_close_artifact_version', 'artifact_id', '
    period_key         varchar(7)    NOT NULL,
    seq                numeric(3,0)  NOT NULL,
    period_end         date          NOT NULL,
    closed_by          varchar(100)  NOT NULL,
    closed_at          timestamptz   NOT NULL,
    known_at           timestamptz   NOT NULL,
    total_debit        numeric(17,2) NOT NULL,
    total_credit       numeric(17,2) NOT NULL,
    trial_balance_hash varchar(64)   NOT NULL,
    content_hash       varchar(64)   NOT NULL,
    report_run_id      varchar(40),
    supersedes_id      uuid          REFERENCES entity_registry (entity_id)');
CREATE UNIQUE INDEX fi_close_artifact_version_once_uk ON fi_close_artifact_version (artifact_id);
CREATE UNIQUE INDEX fi_close_artifact_version_seq_uk ON fi_close_artifact_version (period_key, seq);
CREATE INDEX fi_close_artifact_version_supersedes_idx ON fi_close_artifact_version (supersedes_id);

SELECT finance_create_temporal_table('fi_close_artifact_line_version', 'line_id', '
    artifact_id   uuid           NOT NULL REFERENCES entity_registry (entity_id),
    section       varchar(20)    NOT NULL,
    seq           numeric(6,0)   NOT NULL,
    code          varchar(30)    NOT NULL,
    name          varchar(200),
    debit         numeric(17,2),
    credit        numeric(17,2),
    amount        numeric(17,2),
    ledger_amount numeric(17,2),
    status        varchar(10),
    result        varchar(2000)');
CREATE UNIQUE INDEX fi_close_artifact_line_version_once_uk ON fi_close_artifact_line_version (line_id);
CREATE INDEX fi_close_artifact_line_version_artifact_idx ON fi_close_artifact_line_version (artifact_id, section, seq);

DROP FUNCTION finance_create_temporal_table(text, text, text);
