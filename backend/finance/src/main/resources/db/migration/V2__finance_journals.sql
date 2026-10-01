-- Journal entries (docs/finance/00-design.md section 6.3; ROADMAP F1b): entries and their lines, supporting documents,
-- recurring templates and the postings of finance to the general ledger. Every table is temporal and only ever inserted
-- into (platform docs/design/04-temporal-append-only.md, decision D9).

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

SELECT finance_create_temporal_table('fi_journal_version', 'journal_id', '
    journal_no          varchar(30),
    posting_date        date          NOT NULL,
    document_date       date          NOT NULL,
    description         varchar(500)  NOT NULL,
    source              varchar(20)   NOT NULL,
    status              varchar(12)   NOT NULL,
    preparer            varchar(100)  NOT NULL,
    adjusting           boolean       NOT NULL,
    adjustment_period   boolean       NOT NULL,
    auto_reverse_date   date,
    reverses_journal_id uuid          REFERENCES entity_registry (entity_id),
    reversed_by_id      uuid          REFERENCES entity_registry (entity_id),
    recurring_key       varchar(150),
    total_debit         numeric(19,2) NOT NULL,
    total_credit        numeric(19,2) NOT NULL,
    period_key          varchar(7),
    fiscal_year         numeric(4,0),
    content_hash        varchar(64),
    approval_request_id varchar(36),
    exception_by        varchar(100),
    exception_time      timestamptz,
    exception_reason    varchar(500),
    exception_hash      varchar(64),
    gl_no               varchar(30),
    transaction_id      uuid          REFERENCES entity_registry (entity_id)');
CREATE INDEX fi_journal_version_no_idx ON fi_journal_version (journal_no);
CREATE INDEX fi_journal_version_gl_no_idx ON fi_journal_version (gl_no);
CREATE INDEX fi_journal_version_date_idx ON fi_journal_version (posting_date);
CREATE INDEX fi_journal_version_reverses_idx ON fi_journal_version (reverses_journal_id);
CREATE INDEX fi_journal_version_recurring_idx ON fi_journal_version (recurring_key);
CREATE INDEX fi_journal_version_reverse_date_idx ON fi_journal_version (auto_reverse_date)
    WHERE auto_reverse_date IS NOT NULL;

SELECT finance_create_temporal_table('fi_journal_line_version', 'line_id', '
    journal_id   uuid          NOT NULL REFERENCES entity_registry (entity_id),
    line_no      numeric(4,0)  NOT NULL,
    account_code varchar(20)   NOT NULL,
    debit        numeric(19,2),
    credit       numeric(19,2),
    memo         varchar(200),
    department   varchar(20),
    location     varchar(20)');
CREATE INDEX fi_journal_line_version_journal_idx ON fi_journal_line_version (journal_id);
CREATE INDEX fi_journal_line_version_account_idx ON fi_journal_line_version (account_code);

SELECT finance_create_temporal_table('fi_journal_attachment_version', 'attachment_id', '
    journal_id  uuid         NOT NULL REFERENCES entity_registry (entity_id),
    file_id       uuid,
    sheet_file_id uuid,
    sha256      varchar(64)  NOT NULL,
    description varchar(200)');
CREATE INDEX fi_journal_attachment_version_journal_idx ON fi_journal_attachment_version (journal_id);
CREATE INDEX fi_journal_attachment_version_file_idx ON fi_journal_attachment_version (file_id);
CREATE INDEX fi_journal_attachment_version_sheet_idx ON fi_journal_attachment_version (sheet_file_id);

SELECT finance_create_temporal_table('fi_posting_version', 'posting_id', '
    transaction_id uuid         NOT NULL REFERENCES entity_registry (entity_id),
    posting_date   date         NOT NULL,
    fiscal_year    numeric(4,0) NOT NULL,
    period_no      numeric(2,0) NOT NULL,
    period_key     varchar(7)   NOT NULL,
    source         varchar(4)   NOT NULL,
    gl_no          varchar(30)  NOT NULL,
    document_no    varchar(40),
    source_entity  varchar(100) NOT NULL,
    source_id      varchar(100) NOT NULL');
CREATE INDEX fi_posting_version_transaction_idx ON fi_posting_version (transaction_id);
CREATE INDEX fi_posting_version_gl_no_idx ON fi_posting_version (gl_no);
CREATE INDEX fi_posting_version_period_idx ON fi_posting_version (period_key, posting_date);
CREATE INDEX fi_posting_version_source_idx ON fi_posting_version (source_entity, source_id);

SELECT finance_create_temporal_table('fi_recurring_template_version', 'template_id', '
    template_code varchar(40)  NOT NULL,
    description   varchar(500) NOT NULL,
    start_date    date         NOT NULL,
    end_date      date,
    active        boolean      NOT NULL');
CREATE INDEX fi_recurring_template_version_code_idx ON fi_recurring_template_version (template_code);

SELECT finance_create_temporal_table('fi_recurring_line_version', 'recurring_line_id', '
    template_id  uuid          NOT NULL REFERENCES entity_registry (entity_id),
    line_no      numeric(4,0)  NOT NULL,
    account_code varchar(20)   NOT NULL,
    debit        numeric(19,2),
    credit       numeric(19,2),
    memo         varchar(200),
    department   varchar(20),
    location     varchar(20)');
CREATE INDEX fi_recurring_line_version_template_idx ON fi_recurring_line_version (template_id);

DROP FUNCTION finance_create_temporal_table(text, text, text);
