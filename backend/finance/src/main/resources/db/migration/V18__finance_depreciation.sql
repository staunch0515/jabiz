-- Depreciation (docs/finance/00-design.md section 11; ROADMAP F6b): the monthly runs and their lines per asset
-- (FIN-FA-005), changes in estimate (FIN-FA-006), disposals (FIN-FA-007) and the units used of assets depreciated by
-- units of production. Lines, changes and disposals are written once; a run is reversed by a new version, its entry by
-- the ledger's reversal.

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

SELECT finance_create_temporal_table('fi_depreciation_run_version', 'run_id', '
    period_key    varchar(7)    NOT NULL,
    round         numeric(4,0)  NOT NULL,
    run_no        varchar(20)   NOT NULL,
    posting_date  date          NOT NULL,
    total         numeric(15,2) NOT NULL,
    asset_count   numeric(6,0)  NOT NULL,
    status        varchar(10)   NOT NULL,
    actor         varchar(100)  NOT NULL,
    run_time      timestamptz   NOT NULL,
    reversed_by   varchar(100),
    reason        varchar(500)');
CREATE INDEX fi_depreciation_run_version_period_idx ON fi_depreciation_run_version (period_key, round);
CREATE INDEX fi_depreciation_run_version_no_idx ON fi_depreciation_run_version (run_no);

SELECT finance_create_temporal_table('fi_depreciation_line_version', 'line_id', '
    run_id              uuid          NOT NULL REFERENCES entity_registry (entity_id),
    period_key          varchar(7)    NOT NULL,
    asset_id            uuid          NOT NULL REFERENCES entity_registry (entity_id),
    asset_no            varchar(20)   NOT NULL,
    class_code          varchar(20)   NOT NULL,
    expense_account     varchar(20)   NOT NULL,
    accumulated_account varchar(20)   NOT NULL,
    department          varchar(20),
    amount              numeric(15,2) NOT NULL,
    accumulated         numeric(15,2) NOT NULL,
    units               numeric(15,2),
    previous_through    varchar(7)');
CREATE UNIQUE INDEX fi_depreciation_line_version_once_uk ON fi_depreciation_line_version (line_id);
CREATE UNIQUE INDEX fi_depreciation_line_version_asset_uk ON fi_depreciation_line_version (run_id, asset_id);
CREATE INDEX fi_depreciation_line_version_asset_idx ON fi_depreciation_line_version (asset_id, period_key);
CREATE INDEX fi_depreciation_line_version_period_idx ON fi_depreciation_line_version (period_key);

SELECT finance_create_temporal_table('fi_asset_change_version', 'change_id', '
    asset_id        uuid          NOT NULL REFERENCES entity_registry (entity_id),
    asset_no        varchar(20)   NOT NULL,
    from_period     varchar(7)    NOT NULL,
    old_life_months numeric(4,0)  NOT NULL,
    new_life_months numeric(4,0)  NOT NULL,
    old_salvage     numeric(15,2) NOT NULL,
    new_salvage     numeric(15,2) NOT NULL,
    accumulated_at  numeric(15,2) NOT NULL,
    reason          varchar(500)  NOT NULL,
    actor           varchar(100)  NOT NULL,
    change_time     timestamptz   NOT NULL');
CREATE UNIQUE INDEX fi_asset_change_version_once_uk ON fi_asset_change_version (change_id);
CREATE UNIQUE INDEX fi_asset_change_version_period_uk ON fi_asset_change_version (asset_id, from_period);
CREATE INDEX fi_asset_change_version_no_idx ON fi_asset_change_version (asset_no);

SELECT finance_create_temporal_table('fi_asset_disposal_version', 'disposal_id', '
    asset_id           uuid          NOT NULL REFERENCES entity_registry (entity_id),
    asset_no           varchar(20)   NOT NULL,
    disposal_date      date          NOT NULL,
    kind               varchar(10)   NOT NULL,
    proceeds           numeric(15,2) NOT NULL,
    proceeds_account   varchar(20),
    cost               numeric(15,2) NOT NULL,
    accumulated        numeric(15,2) NOT NULL,
    month_depreciation numeric(15,2) NOT NULL,
    gain_loss          numeric(15,2) NOT NULL,
    reason             varchar(500)  NOT NULL,
    document_no        varchar(40)   NOT NULL,
    actor              varchar(100)  NOT NULL,
    disposal_time      timestamptz   NOT NULL');
CREATE UNIQUE INDEX fi_asset_disposal_version_once_uk ON fi_asset_disposal_version (disposal_id);
CREATE UNIQUE INDEX fi_asset_disposal_version_asset_uk ON fi_asset_disposal_version (asset_id);
CREATE INDEX fi_asset_disposal_version_no_idx ON fi_asset_disposal_version (asset_no);
CREATE INDEX fi_asset_disposal_version_date_idx ON fi_asset_disposal_version (disposal_date);

SELECT finance_create_temporal_table('fi_asset_usage_version', 'usage_id', '
    asset_id    uuid          NOT NULL REFERENCES entity_registry (entity_id),
    asset_no    varchar(20)   NOT NULL,
    period_key  varchar(7)    NOT NULL,
    units       numeric(15,2) NOT NULL,
    actor       varchar(100)  NOT NULL,
    record_time timestamptz   NOT NULL');
CREATE INDEX fi_asset_usage_version_asset_idx ON fi_asset_usage_version (asset_id, period_key);
CREATE INDEX fi_asset_usage_version_period_idx ON fi_asset_usage_version (period_key);

-- The units an asset by units of production has used in the months depreciated.
ALTER TABLE fi_asset_version ADD COLUMN units_used numeric(15,2);

DROP FUNCTION finance_create_temporal_table(text, text, text);
