-- Statement layouts (docs/finance/00-design.md section 14.3; ROADMAP F9b; FIN-RP-002, RP-011): each layout version
-- and its rows, written once; a statement issued with a version is reproduced with it.

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
    EXECUTE format('CREATE UNIQUE INDEX %I ON %I (%I)', p_table || '_once_uk', p_table, p_key);
    PERFORM jabiz_protect_append_only(p_table::regclass);
END $$;

SELECT finance_create_temporal_table('fi_statement_layout_version', 'layout_id', '
    layout_code  varchar(10)  NOT NULL,
    version      numeric(4,0) NOT NULL,
    statement    varchar(20)  NOT NULL,
    title        varchar(200) NOT NULL,
    published_by varchar(100) NOT NULL,
    published_at timestamptz  NOT NULL');
CREATE UNIQUE INDEX fi_statement_layout_version_code_uk ON fi_statement_layout_version (layout_code, version);

SELECT finance_create_temporal_table('fi_statement_layout_row_version', 'layout_row_id', '
    layout_id     uuid         NOT NULL REFERENCES entity_registry (entity_id),
    layout_code   varchar(10)  NOT NULL,
    version       numeric(4,0) NOT NULL,
    seq           numeric(4,0) NOT NULL,
    line_code     varchar(30)  NOT NULL,
    label         varchar(200) NOT NULL,
    kind          varchar(10)  NOT NULL,
    accounts      varchar(500),
    sign          numeric(1,0) NOT NULL,
    detail        boolean      NOT NULL,
    omit_zero     boolean      NOT NULL,
    note_accounts varchar(500)');
CREATE UNIQUE INDEX fi_statement_layout_row_version_line_uk ON fi_statement_layout_row_version (layout_id, line_code);
CREATE INDEX fi_statement_layout_row_version_layout_idx ON fi_statement_layout_row_version (layout_code, version);

DROP FUNCTION finance_create_temporal_table(text, text, text);
