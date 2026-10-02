-- Bank statements, transfers and the cutover's outstanding items (docs/finance/00-design.md section 10; ROADMAP F5a).
-- Every table is temporal and only ever inserted into (platform decision D9); statements, their lines and the cutover
-- are written once, so their uniqueness is also a plain unique index, which holds against concurrent imports.

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

-- The layout a bank account's statements come in; one bank account per cash account (FIN-BK-001).
ALTER TABLE fi_bank_account_version ADD COLUMN statement_format varchar(10);
CREATE INDEX fi_bank_account_version_gl_idx ON fi_bank_account_version (gl_account);

SELECT finance_create_temporal_table('fi_bank_settings_version', 'settings_id', '
    settings_key       varchar(10)  NOT NULL,
    in_transit_account varchar(20),
    match_window_days  numeric(3,0) NOT NULL,
    stale_check_days   numeric(4,0) NOT NULL');
CREATE INDEX fi_bank_settings_version_key_idx ON fi_bank_settings_version (settings_key);

SELECT finance_create_temporal_table('fi_bank_transfer_version', 'transfer_id', '
    transfer_no   varchar(20)   NOT NULL,
    from_bank     varchar(20)   NOT NULL,
    to_bank       varchar(20)   NOT NULL,
    amount        numeric(15,2) NOT NULL,
    currency      varchar(3)    NOT NULL,
    sent_date     date          NOT NULL,
    received_date date,
    description   varchar(500),
    in_transit_account varchar(20),
    status        varchar(12)   NOT NULL,
    prepared_by   varchar(100),
    void_date     date,
    void_reason   varchar(500)');
CREATE INDEX fi_bank_transfer_version_no_idx ON fi_bank_transfer_version (transfer_no);
CREATE INDEX fi_bank_transfer_version_from_idx ON fi_bank_transfer_version (from_bank, sent_date);
CREATE INDEX fi_bank_transfer_version_to_idx ON fi_bank_transfer_version (to_bank, sent_date);

SELECT finance_create_temporal_table('fi_bank_statement_version', 'statement_id', '
    bank_code       varchar(20)   NOT NULL,
    from_date       date          NOT NULL,
    to_date         date          NOT NULL,
    opening_balance numeric(15,2) NOT NULL,
    closing_balance numeric(15,2) NOT NULL,
    line_count      numeric(6,0)  NOT NULL,
    format          varchar(10)   NOT NULL');
CREATE UNIQUE INDEX fi_bank_statement_version_once_uk ON fi_bank_statement_version (statement_id);
CREATE UNIQUE INDEX fi_bank_statement_version_day_uk ON fi_bank_statement_version (bank_code, to_date);

SELECT finance_create_temporal_table('fi_statement_line_version', 'line_id', '
    statement_id   uuid          NOT NULL REFERENCES entity_registry (entity_id),
    bank_code      varchar(20)   NOT NULL,
    line_no        numeric(6,0)  NOT NULL,
    value_date     date          NOT NULL,
    bank_reference varchar(60),
    description    varchar(500),
    amount         numeric(15,2) NOT NULL,
    type_code      varchar(40),
    line_key       varchar(100)  NOT NULL');
CREATE UNIQUE INDEX fi_statement_line_version_once_uk ON fi_statement_line_version (line_id);
CREATE UNIQUE INDEX fi_statement_line_version_key_uk ON fi_statement_line_version (bank_code, line_key);
CREATE INDEX fi_statement_line_version_statement_idx ON fi_statement_line_version (statement_id);
CREATE INDEX fi_statement_line_version_day_idx ON fi_statement_line_version (bank_code, value_date);

SELECT finance_create_temporal_table('fi_bank_opening_version', 'opening_id', '
    bank_code         varchar(20)   NOT NULL,
    cutover_date      date          NOT NULL,
    statement_balance numeric(15,2) NOT NULL,
    book_balance      numeric(15,2) NOT NULL,
    item_count        numeric(6,0)  NOT NULL');
CREATE UNIQUE INDEX fi_bank_opening_version_once_uk ON fi_bank_opening_version (opening_id);
CREATE UNIQUE INDEX fi_bank_opening_version_bank_uk ON fi_bank_opening_version (bank_code);

SELECT finance_create_temporal_table('fi_bank_opening_item_version', 'item_id', '
    opening_id  uuid          NOT NULL REFERENCES entity_registry (entity_id),
    bank_code   varchar(20)   NOT NULL,
    item_date   date          NOT NULL,
    reference   varchar(40)   NOT NULL,
    description varchar(500),
    amount      numeric(15,2) NOT NULL');
CREATE UNIQUE INDEX fi_bank_opening_item_version_once_uk ON fi_bank_opening_item_version (item_id);
CREATE UNIQUE INDEX fi_bank_opening_item_version_ref_uk ON fi_bank_opening_item_version (bank_code, reference);

DROP FUNCTION finance_create_temporal_table(text, text, text);
