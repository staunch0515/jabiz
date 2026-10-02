-- Bank reconciliations (docs/finance/00-design.md section 10; ROADMAP F5c): one per account and statement closing
-- day, prepared and reworked until it is completed, then signed off by a reviewer through the platform's approval;
-- the signed-off report is the archive of REPORT_ISSUE. The reconciliation's history is its versions (decision D9).

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

SELECT finance_create_temporal_table('fi_bank_reconciliation_version', 'reconciliation_id', '
    bank_code            varchar(20)   NOT NULL,
    statement_date       date          NOT NULL,
    statement_balance    numeric(15,2) NOT NULL,
    deposits_in_transit  numeric(15,2) NOT NULL,
    outstanding_payments numeric(15,2) NOT NULL,
    adjusted_balance     numeric(15,2) NOT NULL,
    book_balance         numeric(15,2) NOT NULL,
    not_in_books         numeric(15,2) NOT NULL,
    difference           numeric(15,2) NOT NULL,
    status               varchar(12)   NOT NULL,
    prepared_by          varchar(100),
    approval_request_id  varchar(40),
    content_hash         varchar(64),
    reviewed_by          varchar(100),
    signed_off_time      timestamptz,
    report_run_id        varchar(40),
    report_hash          varchar(64)');
CREATE INDEX fi_bank_reconciliation_version_day_idx ON fi_bank_reconciliation_version (bank_code, statement_date);

DROP FUNCTION finance_create_temporal_table(text, text, text);
