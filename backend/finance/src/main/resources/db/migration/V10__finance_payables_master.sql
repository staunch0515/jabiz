-- The payables' master data (docs/finance/00-design.md section 9; ROADMAP F4a): vendors, their tax information and
-- bank accounts, the 1099 thresholds, the payables settings and the company's bank accounts. Every table is temporal
-- and only ever inserted into (platform decision D9).

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

SELECT finance_create_temporal_table('fi_vendor_version', 'vendor_id', '
    vendor_code       varchar(20)  NOT NULL,
    legal_name        varchar(200) NOT NULL,
    dba_name          varchar(200),
    remit_street      varchar(200),
    remit_city        varchar(100),
    remit_state       varchar(20),
    remit_postal_code varchar(20),
    remit_country     varchar(60),
    contact_name      varchar(100),
    contact_email     varchar(200),
    contact_phone     varchar(40),
    currency          varchar(3)   NOT NULL,
    terms_code        varchar(20)  NOT NULL,
    expense_account   varchar(20),
    payment_method    varchar(10),
    entity_type       varchar(20)  NOT NULL,
    form_1099         varchar(10),
    box_1099          varchar(2),
    w9_on_file        boolean      NOT NULL,
    status            varchar(10)  NOT NULL');
CREATE INDEX fi_vendor_version_code_idx ON fi_vendor_version (vendor_code);

SELECT finance_create_temporal_table('fi_vendor_tax_info_version', 'tax_info_id', '
    vendor_code        varchar(20) NOT NULL,
    tin_type           varchar(10),
    tin                varchar(11),
    w9_file_id         uuid,
    w9_date            date,
    tin_status         varchar(20) NOT NULL,
    backup_withholding boolean     NOT NULL');
CREATE INDEX fi_vendor_tax_info_version_vendor_idx ON fi_vendor_tax_info_version (vendor_code);

SELECT finance_create_temporal_table('fi_vendor_bank_account_version', 'bank_account_id', '
    vendor_code         varchar(20)  NOT NULL,
    bank_name           varchar(100),
    routing_number      varchar(9)   NOT NULL,
    account_number      varchar(17)  NOT NULL,
    account_type        varchar(10)  NOT NULL,
    status              varchar(10)  NOT NULL,
    reason              varchar(500),
    requested_by        varchar(100) NOT NULL,
    requested_time      timestamptz  NOT NULL,
    decided_by          varchar(100),
    approval_request_id varchar(100),
    content_hash        varchar(64)');
CREATE INDEX fi_vendor_bank_account_version_vendor_idx ON fi_vendor_bank_account_version (vendor_code);
CREATE INDEX fi_vendor_bank_account_version_request_idx ON fi_vendor_bank_account_version (approval_request_id);

SELECT finance_create_temporal_table('fi_1099_threshold_version', 'threshold_id', '
    tax_year  numeric(4,0)  NOT NULL,
    form_1099 varchar(10)   NOT NULL,
    threshold numeric(15,2) NOT NULL');
CREATE INDEX fi_1099_threshold_version_year_idx ON fi_1099_threshold_version (tax_year, form_1099);

SELECT finance_create_temporal_table('fi_ap_settings_version', 'settings_id', '
    settings_key       varchar(10) NOT NULL,
    payable_account    varchar(20) NOT NULL,
    discount_account   varchar(20),
    use_tax_account    varchar(20),
    prepayment_account varchar(20),
    default_bank       varchar(20)');
CREATE INDEX fi_ap_settings_version_key_idx ON fi_ap_settings_version (settings_key);

SELECT finance_create_temporal_table('fi_bank_account_version', 'bank_account_id', '
    bank_code        varchar(20)  NOT NULL,
    bank_name        varchar(100) NOT NULL,
    gl_account       varchar(20)  NOT NULL,
    currency         varchar(3)   NOT NULL,
    routing_number   varchar(9)   NOT NULL,
    account_number   varchar(17)  NOT NULL,
    ach_company_id   varchar(10),
    ach_company_name varchar(16),
    next_check_no    numeric(10,0),
    active           boolean      NOT NULL');
CREATE INDEX fi_bank_account_version_code_idx ON fi_bank_account_version (bank_code);

DROP FUNCTION finance_create_temporal_table(text, text, text);
