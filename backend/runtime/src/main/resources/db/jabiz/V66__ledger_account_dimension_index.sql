-- Ledger history of one account for one dimension_1 value (docs/design/11-ledger-events-jobs.md section 1.5; ROADMAP
-- 17zz18). An application that sums everything ever posted to an account for one dimension value (a wallet balance
-- per owner) filters by two equalities, account_id and dimension_1. The existing ledger_entry_version_account_idx
-- (account only) reads every entry of the account, and ledger_entry_version_dimension_1_idx (dimension only) every entry
-- of the value; both stay, they serve the account balance reports and the dimension_balances template.
-- (account_id, dimension_1) reaches exactly the matching entries. No INCLUDE: such a sum runs as a SQL template, whose
-- temporal wrapper filters each row on further columns (is_deleted, effect_start_time), so the scan visits the table
-- whatever the index carries. Partial like the single-dimension index: entries without the dimension are never asked
-- for this way.
--
-- The first CREATE INDEX CONCURRENTLY of the platform's migrations (the earlier ones build plainly, in Flyway's
-- transaction): the table is the largest the platform has, and a plain CREATE INDEX blocks every posting while it
-- builds. CONCURRENTLY cannot run in a transaction, so V66__ledger_account_dimension_index.sql.conf sets
-- executeInTransaction=false and each statement here commits by itself. The keyword is the placeholder ${concurrently}:
-- PlatformSchemaMigration sets it to CONCURRENTLY; tests that migrate several schemas of one database side by side set it
-- empty, since a concurrent build waits for every older transaction of the whole database. The run uses Flyway's
-- session-level advisory lock (PlatformSchemaMigration.NON_TRANSACTIONAL_LOCK): with the default transactional one,
-- Flyway keeps a transaction open on a second connection for the whole run, the build waits for that transaction to
-- end (pg_locks: a ShareLock on its virtualxid, not granted) and Flyway waits for the build, for ever.
--
-- Run again after a failure, this file repairs what the failure left: a build that failed or was killed half way leaves
-- an INVALID index under this name, which "IF NOT EXISTS" alone would keep. The DO block drops the index only when it
-- exists and is INVALID, with a plain DROP INDEX (DROP INDEX CONCURRENTLY cannot run in a DO block): it takes an ACCESS
-- EXCLUSIVE lock on ledger_entry_version for the moment of the drop, waiting for the statements already using the table,
-- and postings wait behind it. A valid index is never dropped. A killed process leaves no history row, so Flyway runs
-- this file again at the next start; a failed statement leaves V66 recorded as failed, and the next start stops at
-- validation until "flyway repair" removes that row (docs/guide/platform-operations.md section 6). The startup check
-- SCHEMA reports any index left INVALID, as a warning.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_index x
               JOIN pg_class i ON i.oid = x.indexrelid
               WHERE i.relname = 'ledger_entry_version_account_dimension_1_idx'
                 AND i.relnamespace = current_schema()::regnamespace
                 AND NOT x.indisvalid) THEN
        DROP INDEX ledger_entry_version_account_dimension_1_idx;
    END IF;
END
$$;
CREATE INDEX ${concurrently} IF NOT EXISTS ledger_entry_version_account_dimension_1_idx
    ON ledger_entry_version (account_id, dimension_1) WHERE dimension_1 IS NOT NULL;
