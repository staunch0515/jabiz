-- Period-end remeasurement (docs/finance/00-design.md section 12; ROADMAP F7c; FIN-FX-005, FX-006): one run per
-- period (FXR-2601) and its lines, each an open foreign currency item with what it carried and what it was remeasured
-- to. The run's entries are found by its number in fi_posting_version.

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

SELECT finance_create_temporal_table('fi_fx_revaluation_run_version', 'run_id', '
    period_key       varchar(7)     NOT NULL,
    run_no           varchar(20)    NOT NULL,
    revaluation_date date           NOT NULL,
    reversal_date    date           NOT NULL,
    rate_type        varchar(10)    NOT NULL,
    tolerance_days   numeric(2,0)   NOT NULL,
    total            numeric(15,2)  NOT NULL,
    line_count       numeric(6,0)   NOT NULL,
    actor            varchar(100)   NOT NULL,
    run_time         timestamptz    NOT NULL');
CREATE UNIQUE INDEX fi_fx_revaluation_run_version_once_uk ON fi_fx_revaluation_run_version (run_id);
CREATE UNIQUE INDEX fi_fx_revaluation_run_version_period_uk ON fi_fx_revaluation_run_version (period_key);
CREATE INDEX fi_fx_revaluation_run_version_no_idx ON fi_fx_revaluation_run_version (run_no);
CREATE INDEX fi_fx_revaluation_run_version_date_idx ON fi_fx_revaluation_run_version (revaluation_date);

SELECT finance_create_temporal_table('fi_fx_revaluation_line_version', 'line_id', '
    run_id        uuid           NOT NULL REFERENCES entity_registry (entity_id),
    period_key    varchar(7)     NOT NULL,
    kind          varchar(10)    NOT NULL,
    document_id   varchar(36)    NOT NULL,
    document_no   varchar(40)    NOT NULL,
    party_code    varchar(20),
    currency      varchar(3)     NOT NULL,
    account       varchar(20)    NOT NULL,
    open_amount   numeric(15,2)  NOT NULL,
    carrying_usd  numeric(15,2)  NOT NULL,
    rate_date     date           NOT NULL,
    rate_type     varchar(10)    NOT NULL,
    rate          numeric(19,10) NOT NULL,
    revalued_usd  numeric(15,2)  NOT NULL,
    difference    numeric(15,2)  NOT NULL');
CREATE UNIQUE INDEX fi_fx_revaluation_line_version_once_uk ON fi_fx_revaluation_line_version (line_id);
CREATE UNIQUE INDEX fi_fx_revaluation_line_version_doc_uk ON fi_fx_revaluation_line_version (run_id, kind, document_id);
CREATE INDEX fi_fx_revaluation_line_version_doc_idx ON fi_fx_revaluation_line_version (document_id);
CREATE INDEX fi_fx_revaluation_line_version_period_idx ON fi_fx_revaluation_line_version (period_key);

DROP FUNCTION finance_create_temporal_table(text, text, text);
