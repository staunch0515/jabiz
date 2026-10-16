-- The journal's export to the accounting system and the top-up receipts (docs/quizbuks/plans/Q9d-journal-receipts.md).
-- All three are written once and only inserted (jabiz_protect_append_only): an export is the summary voucher of one
-- month (JST), the confirmation the voucher number the accounting system gave it, a receipt the document issued for a
-- paid top-up. The indexes keep each of them once whatever a process does: one export per month and voucher number,
-- one confirmation per export, one receipt per top-up.

CREATE FUNCTION quizbuks_create_temporal_table(p_table text, p_key text, p_columns text) RETURNS void
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

-- One month's summary voucher: the period (JST dates, both ends included), the voucher number, the cut-off it took
-- the ledger as recorded at (the next export takes what was recorded after it) and the two archived files.
SELECT quizbuks_create_temporal_table('qb_journal_export_version', 'export_id', '
    period_from        date           NOT NULL,
    period_to          date           NOT NULL,
    granularity        varchar(10)    NOT NULL CHECK (granularity IN (''MONTH'', ''DAY'')),
    voucher_no         varchar(30)    NOT NULL,
    known_at           timestamptz    NOT NULL,
    previous_export_id uuid           REFERENCES entity_registry (entity_id),
    line_count         numeric(9, 0)  NOT NULL,
    transaction_count  numeric(9, 0)  NOT NULL,
    debit_total        numeric(19, 0) NOT NULL,
    credit_total       numeric(19, 0) NOT NULL CHECK (credit_total = debit_total),
    file_id            varchar(36)    NOT NULL,
    file_sha256        varchar(64)    NOT NULL,
    detail_file_id     varchar(36)    NOT NULL,
    detail_sha256      varchar(64)    NOT NULL,
    exported_by        varchar(128)   NOT NULL,
    CHECK (period_to >= period_from)');

SELECT quizbuks_create_temporal_table('qb_journal_export_confirmation_version', 'confirmation_id', '
    export_id           uuid         NOT NULL REFERENCES entity_registry (entity_id),
    external_voucher_no varchar(50)  NOT NULL,
    confirmed_by        varchar(128) NOT NULL');

SELECT quizbuks_create_temporal_table('qb_topup_receipt_version', 'receipt_id', '
    topup_id    varchar(36)  NOT NULL,
    sponsor_id  varchar(128) NOT NULL,
    run_id      varchar(36)  NOT NULL,
    document_no varchar(100) NOT NULL,
    language    varchar(10)  NOT NULL');

-- Written once: one row per key (docs/design/04 section 5.4), and each of the unique facts above once.
CREATE UNIQUE INDEX qb_journal_export_version_once_idx ON qb_journal_export_version (export_id);
CREATE UNIQUE INDEX qb_journal_export_version_period_idx ON qb_journal_export_version (period_from, granularity);
CREATE UNIQUE INDEX qb_journal_export_version_voucher_idx ON qb_journal_export_version (voucher_no);
-- An export follows one other: the chain of exports has no branch.
CREATE UNIQUE INDEX qb_journal_export_version_previous_idx ON qb_journal_export_version (previous_export_id);
CREATE UNIQUE INDEX qb_journal_export_confirmation_version_once_idx
    ON qb_journal_export_confirmation_version (confirmation_id);
CREATE UNIQUE INDEX qb_journal_export_confirmation_version_export_idx
    ON qb_journal_export_confirmation_version (export_id);
CREATE UNIQUE INDEX qb_topup_receipt_version_once_idx ON qb_topup_receipt_version (receipt_id);
CREATE UNIQUE INDEX qb_topup_receipt_version_topup_idx ON qb_topup_receipt_version (topup_id);
CREATE INDEX qb_topup_receipt_version_sponsor_idx ON qb_topup_receipt_version (sponsor_id);

DROP FUNCTION quizbuks_create_temporal_table(text, text, text);
