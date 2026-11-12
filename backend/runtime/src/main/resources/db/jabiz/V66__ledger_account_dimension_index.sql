-- Ledger history of one account for one dimension_1 value (docs/design/11-ledger-events-jobs.md section 1.5; ROADMAP
-- 17zz18). An application that sums everything ever posted to an account for one dimension value (a wallet balance
-- per owner: SELECT direction, sum(amount) FROM ledger_entry_version WHERE account_id = ? AND dimension_1 = ?
-- GROUP BY direction) filters by two equalities. The existing ledger_entry_version_account_idx (account only) reads
-- every entry of the account, and ledger_entry_version_dimension_1_idx (dimension only) every entry of the value;
-- both stay, they serve the account balance reports and the dimension_balances template.
-- (account_id, dimension_1) reaches exactly the matching entries. INCLUDE (direction, amount) lets the sum be read
-- from the index alone (an index-only scan): the ledger is append-only and write-once, so its pages stay all-visible
-- after the vacuum and the scan does not visit the table. Partial like the single-dimension index: entries without
-- the dimension are never asked for this way.
--
-- CONCURRENTLY, the first in the platform's migrations (V62 said there was none): the table is the largest the
-- platform has and a plain CREATE INDEX blocks every posting while it builds. CREATE INDEX CONCURRENTLY cannot run in
-- a transaction, so the file V66__ledger_account_dimension_index.sql.conf sets executeInTransaction=false; this file
-- must therefore hold this one statement only. The run also needs Flyway's session-level advisory lock
-- (PlatformSchemaMigration.NON_TRANSACTIONAL_LOCK): the default transactional one would wait for itself. A build that fails (a deadlock, a cancel) leaves an INVALID index that
-- IF NOT EXISTS then skips: the startup check SCHEMA reports it (docs/guide/platform-operations.md).
CREATE INDEX CONCURRENTLY IF NOT EXISTS ledger_entry_version_account_dimension_1_idx
    ON ledger_entry_version (account_id, dimension_1) INCLUDE (direction, amount) WHERE dimension_1 IS NOT NULL;
