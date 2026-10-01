-- The payables' documents (docs/finance/00-design.md section 9; ROADMAP F4b): bills and vendor credits, their lines,
-- their use tax, credits applied to bills, and the minimal fixed asset register bills capitalize into. Every table is
-- temporal and only ever inserted into (platform decision D9).

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

SELECT finance_create_temporal_table('fi_bill_version', 'bill_id', '
    kind                varchar(10)   NOT NULL,
    bill_no             varchar(40),
    vendor_code         varchar(20)   NOT NULL,
    vendor_invoice_no   varchar(40)   NOT NULL,
    vendor_invoice_key  varchar(40),
    invoice_date        date          NOT NULL,
    received_date       date,
    due_date            date,
    currency            varchar(3)    NOT NULL,
    terms_code          varchar(20)   NOT NULL,
    description         varchar(500),
    original_bill_id    uuid          REFERENCES entity_registry (entity_id),
    form_1099           varchar(10),
    box_1099            varchar(2),
    attachment_file_id  uuid,
    duplicate_reason    varchar(500),
    source              varchar(10)   NOT NULL,
    status              varchar(10)   NOT NULL,
    approval            varchar(15),
    approval_request_id varchar(40),
    prepared_by         varchar(100),
    subtotal            numeric(15,2),
    use_tax_total       numeric(15,2),
    total               numeric(15,2),
    open_amount         numeric(15,2),
    gl_no               varchar(40),
    posted_time         timestamptz,
    transaction_id      uuid          REFERENCES entity_registry (entity_id),
    void_date           date,
    void_reason         varchar(500),
    void_gl_no          varchar(40)');
CREATE INDEX fi_bill_version_no_idx ON fi_bill_version (bill_no);
CREATE INDEX fi_bill_version_vendor_idx ON fi_bill_version (vendor_code, vendor_invoice_key);
CREATE INDEX fi_bill_version_vendor_date_idx ON fi_bill_version (vendor_code, invoice_date);
CREATE INDEX fi_bill_version_original_idx ON fi_bill_version (original_bill_id);

SELECT finance_create_temporal_table('fi_bill_line_version', 'line_id', '
    bill_id     uuid          NOT NULL REFERENCES entity_registry (entity_id),
    line_no     numeric(4,0)  NOT NULL,
    description varchar(500)  NOT NULL,
    amount      numeric(15,2) NOT NULL,
    account     varchar(20)   NOT NULL,
    use_tax_code varchar(20),
    department  varchar(20),
    location    varchar(20),
    form_1099   varchar(10),
    box_1099    varchar(2)');
CREATE INDEX fi_bill_line_version_bill_idx ON fi_bill_line_version (bill_id, line_no);

SELECT finance_create_temporal_table('fi_bill_tax_version', 'tax_id', '
    bill_id      uuid          NOT NULL REFERENCES entity_registry (entity_id),
    line_no      numeric(4,0),
    jurisdiction varchar(20),
    tax_code     varchar(20),
    base         numeric(15,2) NOT NULL,
    rate_percent numeric(7,4),
    rate_from    date,
    tax          numeric(15,2) NOT NULL');
CREATE UNIQUE INDEX fi_bill_tax_version_once_uk ON fi_bill_tax_version (tax_id);
CREATE INDEX fi_bill_tax_version_bill_idx ON fi_bill_tax_version (bill_id);

SELECT finance_create_temporal_table('fi_ap_application_version', 'application_id', '
    source_kind             varchar(20)   NOT NULL,
    source_id               varchar(36)   NOT NULL,
    source_no               varchar(40),
    bill_id                 uuid          NOT NULL REFERENCES entity_registry (entity_id),
    vendor_code             varchar(20)   NOT NULL,
    application_date        date          NOT NULL,
    amount                  numeric(15,2) NOT NULL,
    discount                numeric(15,2),
    reverses_application_id uuid          REFERENCES entity_registry (entity_id),
    reason                  varchar(500)');
CREATE UNIQUE INDEX fi_ap_application_version_once_uk ON fi_ap_application_version (application_id);
CREATE INDEX fi_ap_application_version_bill_idx ON fi_ap_application_version (bill_id);
CREATE INDEX fi_ap_application_version_source_idx ON fi_ap_application_version (source_id);
CREATE INDEX fi_ap_application_version_vendor_idx ON fi_ap_application_version (vendor_code, application_date);

SELECT finance_create_temporal_table('fi_asset_version', 'asset_id', '
    asset_no        varchar(20)   NOT NULL,
    description     varchar(500)  NOT NULL,
    cost_account    varchar(20)   NOT NULL,
    cost            numeric(15,2) NOT NULL,
    in_service_date date          NOT NULL,
    department      varchar(20),
    location        varchar(20),
    source_bill_id  uuid          REFERENCES entity_registry (entity_id),
    source_bill_no  varchar(40),
    vendor_code     varchar(20),
    transaction_id  uuid          REFERENCES entity_registry (entity_id),
    active          boolean       NOT NULL');
CREATE INDEX fi_asset_version_no_idx ON fi_asset_version (asset_no);
CREATE INDEX fi_asset_version_bill_idx ON fi_asset_version (source_bill_id);

DROP FUNCTION finance_create_temporal_table(text, text, text);
