-- The general ledger's master data (docs/finance/00-design.md sections 6 and 7; ROADMAP F1a): the finance side of the
-- ledger accounts, the dimension lists, currencies and exchange rates, fiscal years and periods. Every table is
-- temporal and only ever inserted into (platform docs/design/04-temporal-append-only.md, decision D9).

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

SELECT finance_create_temporal_table('fi_account_version', 'fin_account_id', '
    account_code       varchar(20)  NOT NULL,
    ledger_account_id  uuid         NOT NULL REFERENCES entity_registry (entity_id),
    financial_type     varchar(20)  NOT NULL,
    normal_balance     varchar(10)  NOT NULL,
    statement_line     varchar(100) NOT NULL,
    cash_flow_class    varchar(20),
    control_class      varchar(20),
    clearing           boolean      NOT NULL,
    required_dimension varchar(20)');
CREATE INDEX fi_account_version_code_idx ON fi_account_version (account_code);
CREATE INDEX fi_account_version_ledger_idx ON fi_account_version (ledger_account_id);

SELECT finance_create_temporal_table('fi_department_version', 'department_id', '
    department_code varchar(20)  NOT NULL,
    department_name varchar(100) NOT NULL,
    active          boolean      NOT NULL');
CREATE INDEX fi_department_version_code_idx ON fi_department_version (department_code);

SELECT finance_create_temporal_table('fi_location_version', 'location_id', '
    location_code varchar(20)  NOT NULL,
    location_name varchar(100) NOT NULL,
    active        boolean      NOT NULL');
CREATE INDEX fi_location_version_code_idx ON fi_location_version (location_code);

SELECT finance_create_temporal_table('fi_currency_version', 'currency_id', '
    currency_code varchar(3)   NOT NULL,
    currency_name varchar(100) NOT NULL,
    minor_units   numeric(1,0) NOT NULL,
    active        boolean      NOT NULL');
CREATE INDEX fi_currency_version_code_idx ON fi_currency_version (currency_code);

SELECT finance_create_temporal_table('fi_exchange_rate_version', 'rate_id', '
    from_currency varchar(3)     NOT NULL,
    to_currency   varchar(3)     NOT NULL,
    rate_date     date           NOT NULL,
    rate_type     varchar(10)    NOT NULL,
    rate          numeric(19,10) NOT NULL');
CREATE INDEX fi_exchange_rate_version_pair_idx ON fi_exchange_rate_version (from_currency, to_currency, rate_date);

SELECT finance_create_temporal_table('fi_fiscal_year_version', 'fiscal_year_id', '
    fiscal_year       numeric(4,0) NOT NULL,
    start_date        date         NOT NULL,
    end_date          date         NOT NULL,
    adjustment_period boolean      NOT NULL');
CREATE INDEX fi_fiscal_year_version_year_idx ON fi_fiscal_year_version (fiscal_year);

SELECT finance_create_temporal_table('fi_period_version', 'period_id', '
    fiscal_year_id uuid         NOT NULL REFERENCES entity_registry (entity_id),
    fiscal_year    numeric(4,0) NOT NULL,
    period_no      numeric(2,0) NOT NULL,
    period_key     varchar(7)   NOT NULL,
    adjustment     boolean      NOT NULL,
    start_date     date         NOT NULL,
    end_date       date         NOT NULL,
    status         varchar(12)  NOT NULL,
    ar_status      varchar(6)   NOT NULL,
    ap_status      varchar(6)   NOT NULL,
    bank_status    varchar(6)   NOT NULL,
    fa_status      varchar(6)   NOT NULL');
CREATE INDEX fi_period_version_key_idx ON fi_period_version (period_key);
CREATE INDEX fi_period_version_dates_idx ON fi_period_version (start_date, end_date);

DROP FUNCTION finance_create_temporal_table(text, text, text);
