-- Receipts, write-offs and recurring invoices (docs/finance/00-design.md section 8; ROADMAP F3c), and what the
-- receivables' earlier tables need for them: an application's discount, number and reason; an invoice's recurring key
-- and pending approval; the settings' loss rates. New tables are temporal and only ever inserted into (platform
-- decision D9); a new column leaves the rows sealed before it as they were (docs/design/21 section 2).

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

-- WRITTEN_OFF is longer than the statuses before it; a wider type leaves the sealed values as they were.
ALTER TABLE fi_invoice_version
    ALTER COLUMN status TYPE varchar(15),
    ADD COLUMN recurring_key       varchar(80),
    ADD COLUMN approval            varchar(15),
    ADD COLUMN approval_request_id varchar(40);
CREATE INDEX fi_invoice_version_recurring_idx ON fi_invoice_version (recurring_key);

ALTER TABLE fi_application_version
    ADD COLUMN discount  numeric(15,2),
    ADD COLUMN source_no varchar(40),
    ADD COLUMN reason    varchar(500);
CREATE INDEX fi_application_version_reverses_idx ON fi_application_version (reverses_application_id);

ALTER TABLE fi_ar_settings_version
    ADD COLUMN loss_rate_0 numeric(7,4),
    ADD COLUMN loss_rate_1 numeric(7,4),
    ADD COLUMN loss_rate_2 numeric(7,4),
    ADD COLUMN loss_rate_3 numeric(7,4),
    ADD COLUMN loss_rate_4 numeric(7,4);

SELECT finance_create_temporal_table('fi_receipt_version', 'receipt_id', '
    receipt_no       varchar(40),
    customer_code    varchar(20)   NOT NULL,
    receipt_date     date          NOT NULL,
    amount           numeric(15,2) NOT NULL,
    currency         varchar(3)    NOT NULL,
    method           varchar(10)   NOT NULL,
    reference        varchar(100),
    bank_account     varchar(20)   NOT NULL,
    description      varchar(500),
    unapplied_amount numeric(15,2) NOT NULL,
    status           varchar(10)   NOT NULL,
    prepared_by      varchar(100),
    void_date        date,
    void_reason      varchar(500),
    void_gl_no       varchar(40)');
CREATE INDEX fi_receipt_version_no_idx ON fi_receipt_version (receipt_no);
CREATE INDEX fi_receipt_version_customer_idx ON fi_receipt_version (customer_code, receipt_date);

SELECT finance_create_temporal_table('fi_write_off_version', 'write_off_id', '
    invoice_id          uuid          NOT NULL REFERENCES entity_registry (entity_id),
    invoice_no          varchar(40)   NOT NULL,
    customer_code       varchar(20)   NOT NULL,
    write_off_date      date          NOT NULL,
    amount              numeric(15,2) NOT NULL,
    reason              varchar(500)  NOT NULL,
    status              varchar(10)   NOT NULL,
    requested_by        varchar(100),
    approval_request_id varchar(40),
    content_hash        varchar(64),
    refusal             varchar(500),
    gl_no               varchar(40),
    transaction_id      uuid          REFERENCES entity_registry (entity_id),
    application_id      uuid          REFERENCES entity_registry (entity_id),
    recovered_amount    numeric(15,2)');
CREATE INDEX fi_write_off_version_invoice_idx ON fi_write_off_version (invoice_id);

SELECT finance_create_temporal_table('fi_recurring_invoice_version', 'template_id', '
    template_code varchar(40)  NOT NULL,
    customer_code varchar(20)  NOT NULL,
    description   varchar(500) NOT NULL,
    invoice_day   numeric(2,0) NOT NULL,
    start_date    date         NOT NULL,
    end_date      date,
    active        boolean      NOT NULL');
CREATE INDEX fi_recurring_invoice_version_code_idx ON fi_recurring_invoice_version (template_code);

SELECT finance_create_temporal_table('fi_recurring_invoice_line_version', 'template_line_id', '
    template_id     uuid          NOT NULL REFERENCES entity_registry (entity_id),
    line_no         numeric(4,0)  NOT NULL,
    description     varchar(500)  NOT NULL,
    quantity        numeric(15,4) NOT NULL,
    unit_price      numeric(17,4) NOT NULL,
    revenue_account varchar(20)   NOT NULL,
    tax_code        varchar(20),
    department      varchar(20),
    location        varchar(20)');
CREATE INDEX fi_recurring_invoice_line_version_template_idx
    ON fi_recurring_invoice_line_version (template_id, line_no);

DROP FUNCTION finance_create_temporal_table(text, text, text);
