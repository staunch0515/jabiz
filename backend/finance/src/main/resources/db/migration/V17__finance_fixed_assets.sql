-- Fixed assets (docs/finance/00-design.md section 11; ROADMAP F6a): asset classes with their accounts and
-- defaults, the asset settings, and the register's depreciation terms. Assets of the bills before F6 keep empty terms
-- until classified (F6 plan decision D4); the history of an asset is its versions (decision D9).

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

SELECT finance_create_temporal_table('fi_asset_class_version', 'class_id', '
    class_code          varchar(20)   NOT NULL,
    class_name          varchar(100)  NOT NULL,
    cost_account        varchar(20)   NOT NULL,
    accumulated_account varchar(20)   NOT NULL,
    expense_account     varchar(20)   NOT NULL,
    method              varchar(6)    NOT NULL,
    life_months         numeric(4,0)  NOT NULL,
    convention          varchar(12)   NOT NULL,
    threshold           numeric(15,2) NOT NULL,
    active              boolean       NOT NULL');
CREATE INDEX fi_asset_class_version_code_idx ON fi_asset_class_version (class_code);
CREATE INDEX fi_asset_class_version_cost_idx ON fi_asset_class_version (cost_account);

SELECT finance_create_temporal_table('fi_fa_settings_version', 'settings_id', '
    settings_key      varchar(10) NOT NULL,
    gain_loss_account varchar(20)');
CREATE INDEX fi_fa_settings_version_key_idx ON fi_fa_settings_version (settings_key);

ALTER TABLE fi_asset_version
    ADD COLUMN custodian           varchar(100),
    ADD COLUMN source              varchar(12),
    ADD COLUMN class_code          varchar(20),
    ADD COLUMN method              varchar(6),
    ADD COLUMN life_months         numeric(4,0),
    ADD COLUMN salvage             numeric(15,2),
    ADD COLUMN convention          varchar(12),
    ADD COLUMN total_units         numeric(15,2),
    ADD COLUMN status              varchar(20),
    ADD COLUMN opening_accumulated numeric(15,2),
    ADD COLUMN opening_period      varchar(7),
    ADD COLUMN depreciated_through varchar(7),
    ADD COLUMN accumulated         numeric(15,2);
CREATE INDEX fi_asset_version_class_idx ON fi_asset_version (class_code);

DROP FUNCTION finance_create_temporal_table(text, text, text);
