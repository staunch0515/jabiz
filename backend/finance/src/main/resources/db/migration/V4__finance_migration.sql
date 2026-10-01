-- The opening of the books and the migration's decisions (docs/finance/00-design.md section 13; ROADMAP F2a).

-- Period 0 of the first fiscal year, the day before it: it holds only the opening entry (FIN-PC-002). Existing
-- periods are regular ones.
ALTER TABLE fi_period_version ADD COLUMN opening boolean NOT NULL DEFAULT false;
CREATE INDEX fi_period_version_opening_idx ON fi_period_version (opening) WHERE opening;

-- How the migration reads a legacy value (FIN-DI-003): one decision per kind and legacy value, changed only through
-- FIN_MIGRATION_DECIDE; temporal, so earlier decisions stay in the history.
CREATE TABLE fi_migration_decision_version (
    row_id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    decision_id       uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer      NOT NULL,
    effect_start_time timestamptz  NOT NULL,
    created_time      timestamptz  NOT NULL,
    process_seq_id    bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean      NOT NULL DEFAULT false,
    kind              varchar(20)  NOT NULL,
    legacy_value      varchar(100) NOT NULL,
    decided_value     varchar(100) NOT NULL,
    reason            varchar(500) NOT NULL,
    decided_by        varchar(100) NOT NULL,
    decided_at        timestamptz  NOT NULL,
    CONSTRAINT fi_migration_decision_version_uk UNIQUE (decision_id, version_no)
);
CREATE INDEX fi_migration_decision_version_current_idx
    ON fi_migration_decision_version (decision_id, effect_start_time DESC, version_no DESC);
CREATE INDEX fi_migration_decision_version_process_idx ON fi_migration_decision_version (process_seq_id);
CREATE INDEX fi_migration_decision_version_value_idx ON fi_migration_decision_version (kind, legacy_value);
SELECT jabiz_protect_append_only('fi_migration_decision_version');
