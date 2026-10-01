-- Temporal data at volume (decision D29, docs/design/04-temporal-append-only.md sections 5.3 and 7).

-- Ledger transactions and entries are written once: posted, corrected by a reversal, never given a second version.
-- The key alone is unique, which lets reads take each instance's version without picking the latest of many.
-- A database where an instance already had two versions stops here, naming the duplicate key.
CREATE UNIQUE INDEX ledger_transaction_version_once_uk ON ledger_transaction_version (transaction_id);
CREATE UNIQUE INDEX ledger_entry_version_once_uk ON ledger_entry_version (entry_id);

-- Uniqueness of temporal entities is checked from the versions that hold the values (decision D29): an index on the
-- constraint's fields keeps that check from reading every version.
CREATE INDEX sys_approval_rule_version_code_idx ON sys_approval_rule_version (rule_code);
CREATE INDEX sys_sod_rule_version_code_idx ON sys_sod_rule_version (rule_code);
