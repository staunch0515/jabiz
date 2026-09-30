-- Ledger enhancements, part 1 (docs/design/11-ledger-events-jobs.md section 1; decision D24): an account hierarchy
-- with summary accounts, the source document of a transaction, a memo and up to four analysis dimensions per entry.
-- The tables are append-only (decision D5): existing rows keep NULL, which means "postable", "no source", "none".

ALTER TABLE ledger_account_version
    ADD COLUMN parent_id uuid REFERENCES entity_registry (entity_id),
    ADD COLUMN summary   boolean;
CREATE INDEX ledger_account_version_parent_idx ON ledger_account_version (parent_id);

ALTER TABLE ledger_transaction_version
    ADD COLUMN source_entity varchar(100),
    ADD COLUMN source_id     varchar(100);
CREATE INDEX ledger_transaction_version_source_idx ON ledger_transaction_version (source_entity, source_id);
CREATE INDEX ledger_transaction_version_created_idx ON ledger_transaction_version (created_time);

ALTER TABLE ledger_entry_version
    ADD COLUMN memo        varchar(500),
    ADD COLUMN dimension_1 varchar(100),
    ADD COLUMN dimension_2 varchar(100),
    ADD COLUMN dimension_3 varchar(100),
    ADD COLUMN dimension_4 varchar(100);
CREATE INDEX ledger_entry_version_dimension_1_idx ON ledger_entry_version (dimension_1) WHERE dimension_1 IS NOT NULL;
CREATE INDEX ledger_entry_version_dimension_2_idx ON ledger_entry_version (dimension_2) WHERE dimension_2 IS NOT NULL;
CREATE INDEX ledger_entry_version_dimension_3_idx ON ledger_entry_version (dimension_3) WHERE dimension_3 IS NOT NULL;
CREATE INDEX ledger_entry_version_dimension_4_idx ON ledger_entry_version (dimension_4) WHERE dimension_4 IS NOT NULL;
