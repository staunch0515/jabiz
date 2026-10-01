-- The receivables' and sales tax master data (docs/finance/00-design.md section 8; ROADMAP F3a): customers, payment
-- terms, exemption certificates, the receivables settings, tax jurisdictions, their rates and the tax codes. Every
-- table is temporal and only ever inserted into (platform decision D9).

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

SELECT finance_create_temporal_table('fi_customer_version', 'customer_id', '
    customer_code         varchar(20)   NOT NULL,
    legal_name            varchar(200)  NOT NULL,
    billing_street        varchar(200),
    billing_city          varchar(100),
    billing_state         varchar(20),
    billing_postal_code   varchar(20),
    billing_country       varchar(60),
    shipping_street       varchar(200),
    shipping_city         varchar(100),
    shipping_state        varchar(20),
    shipping_postal_code  varchar(20),
    shipping_country      varchar(60),
    contact_name          varchar(100),
    contact_email         varchar(200),
    contact_phone         varchar(40),
    currency              varchar(3)    NOT NULL,
    terms_code            varchar(20)   NOT NULL,
    credit_limit          numeric(15,2),
    tax_code              varchar(20)   NOT NULL,
    status                varchar(10)   NOT NULL');
CREATE INDEX fi_customer_version_code_idx ON fi_customer_version (customer_code);

SELECT finance_create_temporal_table('fi_payment_terms_version', 'terms_id', '
    terms_code       varchar(20)  NOT NULL,
    description      varchar(100) NOT NULL,
    net_days         numeric(3,0) NOT NULL,
    discount_percent numeric(5,2),
    discount_days    numeric(3,0),
    end_of_month     boolean      NOT NULL,
    active           boolean      NOT NULL');
CREATE INDEX fi_payment_terms_version_code_idx ON fi_payment_terms_version (terms_code);

SELECT finance_create_temporal_table('fi_exemption_certificate_version', 'certificate_id', '
    customer_code    varchar(20)  NOT NULL,
    state            varchar(2)   NOT NULL,
    certificate_no   varchar(40)  NOT NULL,
    certificate_type varchar(30)  NOT NULL,
    description      varchar(200),
    file_id          uuid,
    issue_date       date,
    expiry_date      date,
    active           boolean      NOT NULL');
CREATE INDEX fi_exemption_certificate_version_customer_idx
    ON fi_exemption_certificate_version (customer_code, state, certificate_no);

SELECT finance_create_temporal_table('fi_ar_settings_version', 'settings_id', '
    settings_key           varchar(10) NOT NULL,
    receivable_account     varchar(20) NOT NULL,
    allowance_account      varchar(20) NOT NULL,
    returns_account        varchar(20) NOT NULL,
    sales_tax_account      varchar(20) NOT NULL,
    unapplied_cash_account varchar(20),
    discount_account       varchar(20),
    missing_certificate    varchar(10) NOT NULL,
    credit_limit_check     varchar(10) NOT NULL');
CREATE INDEX fi_ar_settings_version_key_idx ON fi_ar_settings_version (settings_key);

SELECT finance_create_temporal_table('fi_tax_jurisdiction_version', 'jurisdiction_id', '
    jurisdiction_code varchar(20)  NOT NULL,
    jurisdiction_name varchar(200) NOT NULL,
    level             varchar(10)  NOT NULL,
    state             varchar(2)   NOT NULL,
    active            boolean      NOT NULL');
CREATE INDEX fi_tax_jurisdiction_version_code_idx ON fi_tax_jurisdiction_version (jurisdiction_code);

SELECT finance_create_temporal_table('fi_tax_rate_version', 'rate_id', '
    jurisdiction_code varchar(20)  NOT NULL,
    effective_from    date         NOT NULL,
    effective_to      date,
    rate_percent      numeric(7,4) NOT NULL');
CREATE INDEX fi_tax_rate_version_jurisdiction_idx ON fi_tax_rate_version (jurisdiction_code, effective_from);

SELECT finance_create_temporal_table('fi_tax_code_version', 'tax_code_id', '
    tax_code             varchar(20)  NOT NULL,
    description          varchar(200) NOT NULL,
    kind                 varchar(15)  NOT NULL,
    reason               varchar(30),
    state                varchar(2),
    jurisdictions        varchar(200),
    certificate_required boolean      NOT NULL,
    charge_code          varchar(20),
    active               boolean      NOT NULL');
CREATE INDEX fi_tax_code_version_code_idx ON fi_tax_code_version (tax_code);

DROP FUNCTION finance_create_temporal_table(text, text, text);
