-- Payments (docs/finance/00-design.md section 9; ROADMAP F4c): payment runs and their lines, the payments a released
-- run posts, and the files given to the bank (kept by the platform's generated file archive). Every table is temporal
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

SELECT finance_create_temporal_table('fi_payment_run_version', 'run_id', '
    run_no              varchar(20)   NOT NULL,
    bank_code           varchar(20)   NOT NULL,
    payment_date        date          NOT NULL,
    method              varchar(10)   NOT NULL,
    description         varchar(500),
    status              varchar(10)   NOT NULL,
    total               numeric(15,2),
    line_count          numeric(6,0),
    prepared_by         varchar(100),
    approval_request_id varchar(40),
    content_hash        varchar(64),
    approved_by         varchar(100),
    released_by         varchar(100),
    released_time       timestamptz,
    cancel_reason       varchar(500)');
CREATE INDEX fi_payment_run_version_no_idx ON fi_payment_run_version (run_no);
CREATE INDEX fi_payment_run_version_status_idx ON fi_payment_run_version (status);

SELECT finance_create_temporal_table('fi_payment_line_version', 'line_id', '
    run_id      uuid          NOT NULL REFERENCES entity_registry (entity_id),
    kind        varchar(15)   NOT NULL,
    bill_id     uuid          REFERENCES entity_registry (entity_id),
    bill_no     varchar(40),
    vendor_code varchar(20),
    payee       varchar(200),
    account     varchar(20),
    description varchar(500),
    amount      numeric(15,2) NOT NULL,
    discount    numeric(15,2)');
CREATE INDEX fi_payment_line_version_run_idx ON fi_payment_line_version (run_id);
CREATE INDEX fi_payment_line_version_bill_idx ON fi_payment_line_version (bill_id);

SELECT finance_create_temporal_table('fi_payment_version', 'payment_id', '
    payment_no             varchar(20)   NOT NULL,
    run_id                 uuid          NOT NULL REFERENCES entity_registry (entity_id),
    run_no                 varchar(20)   NOT NULL,
    kind                   varchar(15)   NOT NULL,
    vendor_code            varchar(20),
    payee                  varchar(200)  NOT NULL,
    bank_code              varchar(20)   NOT NULL,
    method                 varchar(10)   NOT NULL,
    payment_date           date          NOT NULL,
    amount                 numeric(15,2) NOT NULL,
    discount               numeric(15,2),
    check_no               varchar(20),
    vendor_bank_account_id uuid          REFERENCES entity_registry (entity_id),
    open_amount            numeric(15,2),
    status                 varchar(10)   NOT NULL,
    void_date              date,
    void_reason            varchar(500),
    void_gl_no             varchar(40)');
CREATE INDEX fi_payment_version_no_idx ON fi_payment_version (payment_no);
CREATE INDEX fi_payment_version_run_idx ON fi_payment_version (run_id);
CREATE INDEX fi_payment_version_vendor_idx ON fi_payment_version (vendor_code, payment_date);

SELECT finance_create_temporal_table('fi_payment_file_version', 'payment_file_id', '
    run_id            uuid          NOT NULL REFERENCES entity_registry (entity_id),
    run_no            varchar(20)   NOT NULL,
    file_kind         varchar(15)   NOT NULL,
    bank_code         varchar(20)   NOT NULL,
    generated_date    date          NOT NULL,
    generated_file_id varchar(36)   NOT NULL,
    file_name         varchar(200)  NOT NULL,
    sha256            varchar(64)   NOT NULL,
    entry_count       numeric(6,0),
    total             numeric(15,2),
    status            varchar(10)   NOT NULL,
    generated_by      varchar(100)  NOT NULL,
    cancelled_by      varchar(100),
    cancel_reason     varchar(500)');
CREATE INDEX fi_payment_file_version_run_idx ON fi_payment_file_version (run_id, file_kind);
CREATE INDEX fi_payment_file_version_day_idx ON fi_payment_file_version (bank_code, generated_date, file_kind);

DROP FUNCTION finance_create_temporal_table(text, text, text);
