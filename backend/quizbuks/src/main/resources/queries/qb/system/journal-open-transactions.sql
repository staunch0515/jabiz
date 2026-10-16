/*---
id: qb.system.journal-open-transactions
description: >-
  Whether another transaction of this database has been open for a while (Q9d, review M1): how many client
  transactions besides the caller's have been open for at least olderThanSeconds, and the oldest one's age in seconds;
  the journal export refuses while one is (a posting it began could be lost from every export)
entities: [LedgerTransaction]
params:
  olderThanSeconds: { kind: { type: numeric, precision: 9, scale: 0 }, required: true, description: "How long counts as long (seconds)" }
results:
  openCount:     { kind: { type: numeric, precision: 9, scale: 0 } }
  oldestSeconds: { kind: { type: numeric, precision: 12, scale: 0 } }
permissions: [qb.journal.export]
---*/
-- The server's own view of its sessions (the application's role sees every session of its own role). Ages are the
-- database's elapsed time, not business time: they only compare a transaction's duration with the export's margin.
-- LedgerTransaction is named for the header only (a template reads entities); nothing of it is read.
SELECT
    count(*) AS openCount,
    coalesce(floor(max(extract(epoch FROM clock_timestamp() - a.xact_start))), 0) AS oldestSeconds
FROM pg_stat_activity a
WHERE a.datname = current_database()
  AND a.backend_type = 'client backend'
  AND a.pid <> pg_backend_pid()
  AND a.xact_start IS NOT NULL
  AND extract(epoch FROM clock_timestamp() - a.xact_start) >= :olderThanSeconds
