-- The year-end close (docs/finance/00-design.md section 7.3; ROADMAP F8c; FIN-PC-008): the close settings (the
-- retained earnings account) and each year's closes, written once, with the closing entry each posted.

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

SELECT finance_create_temporal_table('fi_close_settings_version', 'settings_id', '
    settings_key              varchar(10) NOT NULL,
    retained_earnings_account varchar(20)');
CREATE INDEX fi_close_settings_version_key_idx ON fi_close_settings_version (settings_key);

SELECT finance_create_temporal_table('fi_year_close_version', 'year_close_id', '
    fiscal_year               numeric(4,0)  NOT NULL,
    seq                       numeric(3,0)  NOT NULL,
    journal_id                varchar(40),
    journal_no                varchar(40),
    reversal_journal_no       varchar(40),
    net_income                numeric(17,2) NOT NULL,
    retained_earnings_account varchar(20)   NOT NULL,
    artifact_id               uuid          NOT NULL REFERENCES entity_registry (entity_id),
    closed_by                 varchar(100)  NOT NULL,
    closed_at                 timestamptz   NOT NULL');
CREATE UNIQUE INDEX fi_year_close_version_once_uk ON fi_year_close_version (year_close_id);
CREATE UNIQUE INDEX fi_year_close_version_seq_uk ON fi_year_close_version (fiscal_year, seq);

DROP FUNCTION finance_create_temporal_table(text, text, text);
