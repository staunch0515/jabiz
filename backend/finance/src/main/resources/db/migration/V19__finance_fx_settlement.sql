-- Foreign currency settlement (docs/finance/00-design.md section 12; ROADMAP F7a): the settings of the exchange gain
-- and loss accounts and of how rates are taken (F7 plan decisions D1, D2); a receipt's rate and US dollars; and on
-- every application what the source gave in US dollars and the realized difference (FIN-FX-004); a journal line's
-- foreign currency, amount and rate (D5).

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

SELECT finance_create_temporal_table('fi_fx_settings_version', 'settings_id', '
    settings_key          varchar(10)  NOT NULL,
    realized_account      varchar(20),
    unrealized_account    varchar(20),
    revaluation_rate_type varchar(10)  NOT NULL,
    tolerance_days        numeric(2,0) NOT NULL');
CREATE INDEX fi_fx_settings_version_key_idx ON fi_fx_settings_version (settings_key);

ALTER TABLE fi_receipt_version
    ADD COLUMN exchange_rate        numeric(19,10),
    ADD COLUMN amount_usd           numeric(15,2),
    ADD COLUMN unapplied_amount_usd numeric(15,2);

ALTER TABLE fi_application_version
    ADD COLUMN source_amount_usd numeric(15,2),
    ADD COLUMN fx_gain_loss      numeric(15,2);

ALTER TABLE fi_journal_line_version
    ADD COLUMN currency       varchar(3),
    ADD COLUMN foreign_amount numeric(15,2),
    ADD COLUMN exchange_rate  numeric(19,10);

DROP FUNCTION finance_create_temporal_table(text, text, text);
