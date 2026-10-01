-- The receivables' documents (docs/finance/00-design.md section 8; ROADMAP F3b): invoices and credit memos with their
-- lines, the tax computed for each, and the credits applied to invoices. Every table is temporal and only ever inserted
-- into (platform decision D9); tax rows and applications are written once (decision D29).

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

SELECT finance_create_temporal_table('fi_invoice_version', 'invoice_id', '
    kind                varchar(15)   NOT NULL,
    invoice_no          varchar(40),
    customer_code       varchar(20)   NOT NULL,
    invoice_date        date          NOT NULL,
    due_date            date,
    currency            varchar(3)    NOT NULL,
    exchange_rate       numeric(19,10),
    terms_code          varchar(20)   NOT NULL,
    tax_code            varchar(20)   NOT NULL,
    description         varchar(500),
    reference           varchar(100),
    original_invoice_id uuid          REFERENCES entity_registry (entity_id),
    source              varchar(10)   NOT NULL,
    status              varchar(10)   NOT NULL,
    subtotal            numeric(15,2),
    tax_total           numeric(15,2),
    total               numeric(15,2),
    total_usd           numeric(15,2),
    open_amount         numeric(15,2),
    open_amount_usd     numeric(15,2),
    gl_no               varchar(40),
    transaction_id      uuid          REFERENCES entity_registry (entity_id),
    void_date           date,
    void_reason         varchar(500),
    void_gl_no          varchar(40)');
CREATE INDEX fi_invoice_version_no_idx ON fi_invoice_version (invoice_no);
CREATE INDEX fi_invoice_version_customer_idx ON fi_invoice_version (customer_code);
CREATE INDEX fi_invoice_version_original_idx ON fi_invoice_version (original_invoice_id);

SELECT finance_create_temporal_table('fi_invoice_line_version', 'line_id', '
    invoice_id      uuid          NOT NULL REFERENCES entity_registry (entity_id),
    line_no         numeric(4,0)  NOT NULL,
    description     varchar(500)  NOT NULL,
    quantity        numeric(15,4) NOT NULL,
    unit_price      numeric(17,4) NOT NULL,
    amount          numeric(15,2),
    revenue_account varchar(20)   NOT NULL,
    tax_code        varchar(20),
    department      varchar(20),
    location        varchar(20)');
CREATE INDEX fi_invoice_line_version_invoice_idx ON fi_invoice_line_version (invoice_id, line_no);

SELECT finance_create_temporal_table('fi_invoice_tax_version', 'tax_id', '
    invoice_id     uuid          NOT NULL REFERENCES entity_registry (entity_id),
    line_no        numeric(4,0),
    jurisdiction   varchar(20),
    tax_code       varchar(20),
    tax_kind       varchar(15),
    reason         varchar(30),
    certificate_no varchar(40),
    base           numeric(15,2) NOT NULL,
    rate_percent   numeric(7,4),
    rate_from      date,
    tax            numeric(15,2) NOT NULL');
CREATE UNIQUE INDEX fi_invoice_tax_version_once_uk ON fi_invoice_tax_version (tax_id);
CREATE INDEX fi_invoice_tax_version_invoice_idx ON fi_invoice_tax_version (invoice_id);

SELECT finance_create_temporal_table('fi_application_version', 'application_id', '
    source_kind             varchar(20)   NOT NULL,
    source_id               varchar(36)   NOT NULL,
    invoice_id              uuid          NOT NULL REFERENCES entity_registry (entity_id),
    customer_code           varchar(20)   NOT NULL,
    application_date        date          NOT NULL,
    amount                  numeric(15,2) NOT NULL,
    amount_usd              numeric(15,2) NOT NULL,
    reverses_application_id uuid          REFERENCES entity_registry (entity_id)');
CREATE UNIQUE INDEX fi_application_version_once_uk ON fi_application_version (application_id);
CREATE INDEX fi_application_version_invoice_idx ON fi_application_version (invoice_id);
CREATE INDEX fi_application_version_source_idx ON fi_application_version (source_id);
CREATE INDEX fi_application_version_customer_idx ON fi_application_version (customer_code, application_date);

DROP FUNCTION finance_create_temporal_table(text, text, text);
