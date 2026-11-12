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
-- session-level advisory lock (PlatformScriptPlaceholders.NON_TRANSACTIONAL_LOCK): with the default transactional one,
-- Flyway keeps a transaction open on a second connection for the whole run, the build waits for that transaction to
-- end (pg_locks: a ShareLock on its virtualxid, not granted) and Flyway waits for the build, for ever.
--
-- Run again after a failure, this file repairs what the failure left: a build that failed or was killed half way leaves
-- an INVALID index under this name, which "IF NOT EXISTS" alone would keep. The DO block drops the index only when it
-- exists and is INVALID; a valid index is never dropped. It uses a plain DROP INDEX (DROP INDEX CONCURRENTLY cannot run
-- in a DO block), which takes an ACCESS EXCLUSIVE lock on ledger_entry_version; that is brief only when no session is
-- still building the index. After the migrating process is killed, its server backend may go on with the CREATE INDEX
-- CONCURRENTLY until it has scanned the table, holding a SHARE UPDATE EXCLUSIVE lock on it; a DROP queued behind that
-- would hold up every read and write of the ledger. So the block first looks for a client session holding that lock
-- and stops with an error instead (a manual VACUUM or another index build holds it too; autovacuum, which gives way,
-- does not count). A holder whose kind this role cannot see (pg_stat_activity shows other roles' sessions in full only
-- to pg_read_all_stats) is left to the backstop: the drop waits for its lock at most ${drop_lock_timeout} (placeholder,
-- PlatformSchemaMigration sets 3s) before failing. Either failure records V66 as
-- failed: once nothing builds the index any more, "flyway repair" removes that row and the next start drops and builds
-- (docs/guide/platform-operations.md section 6). A killed process leaves no history row, so Flyway runs this file again
-- at the next start. The startup check SCHEMA reports any index left INVALID, as a warning.
DO $$
DECLARE
    builder integer;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_index x
               JOIN pg_class i ON i.oid = x.indexrelid
               WHERE i.relname = 'ledger_entry_version_account_dimension_1_idx'
                 AND i.relnamespace = current_schema()::regnamespace
                 AND NOT x.indisvalid) THEN
        SELECT l.pid INTO builder
        FROM pg_locks l
        JOIN pg_stat_activity a ON a.pid = l.pid
        WHERE l.locktype = 'relation'
          AND l.database = (SELECT oid FROM pg_database WHERE datname = current_database())
          AND l.relation = (current_schema() || '.ledger_entry_version')::regclass
          AND l.mode = 'ShareUpdateExclusiveLock' AND l.granted
          AND l.pid <> pg_backend_pid()
          AND a.backend_type = 'client backend'
        LIMIT 1;
        IF builder IS NOT NULL THEN
            RAISE EXCEPTION 'The INVALID index ledger_entry_version_account_dimension_1_idx is still being built by '
                'another session, or a session is running VACUUM on the table or building another index (pid %): '
                'wait for it to finish (see pg_stat_activity), or cancel it with '
                'pg_cancel_backend(%), then run flyway repair on jabiz_schema_history and restart '
                '(docs/guide/platform-operations.md section 6)', builder, builder;
        END IF;
        PERFORM set_config('lock_timeout', '${drop_lock_timeout}', true);
        DROP INDEX ledger_entry_version_account_dimension_1_idx;
    END IF;
END
$$;
CREATE INDEX ${concurrently} IF NOT EXISTS ledger_entry_version_account_dimension_1_idx
    ON ledger_entry_version (account_id, dimension_1) WHERE dimension_1 IS NOT NULL;
