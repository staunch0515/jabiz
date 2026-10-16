/*---
id: qb.system.journal-parts
description: >-
  How a journal export's detail is cut into parts (Q9d, review H1): consecutive ranges of the transactions it takes,
  in the order of their keys, each part within a budget of rows (the platform's read limit) and of bytes (its limit of
  a generated file), counted per transaction from the detail rows it writes - one row per part
entities: [LedgerAccount, LedgerTransaction, LedgerEntry]
params:
  to:              { like: LedgerTransaction.bookingTime, required: true, description: "The period's end: transactions booked before it" }
  knownAt:         { like: LedgerTransaction.bookingTime, required: true, description: "The export's cut-off: transactions recorded by then" }
  previousTo:      { like: LedgerTransaction.bookingTime, description: "The previous export's period end (none for the first)" }
  previousKnownAt: { like: LedgerTransaction.bookingTime, description: "The previous export's cut-off (none for the first)" }
  partRows:        { kind: { type: numeric, precision: 12, scale: 0 }, required: true, description: "Rows a part aims at (half the read limit)" }
  partBytes:       { kind: { type: numeric, precision: 15, scale: 0 }, required: true, description: "Bytes a part aims at (half a file's limit)" }
results:
  partNo:           { kind: { type: numeric, precision: 9, scale: 0 } }
  firstTransaction: { from: LedgerTransaction.transactionId }
  lastTransaction:  { from: LedgerTransaction.transactionId }
  transactions:     { kind: { type: numeric, precision: 12, scale: 0 } }
  rowCount:         { kind: { type: numeric, precision: 12, scale: 0 } }
  bytes:            { kind: { type: numeric, precision: 19, scale: 0 } }
  maxTransactionRows:  { kind: { type: numeric, precision: 12, scale: 0 } }
  maxTransactionBytes: { kind: { type: numeric, precision: 19, scale: 0 } }
permissions: [qb.journal.export]
---*/
-- The same transactions as qb.system.journal-detail takes (see there), and its rows: one per transaction, account,
-- side and (on 2110) sponsor. A row's bytes are an upper bound of its CSV line (JournalLines.detailCsv): 260 for the
-- fixed cells, quotes and separators, then each text cell with its doubled quotes and a possible leading "'".
-- Each transaction costs the larger of its share of the part's rows and of its bytes; a part is the transactions whose
-- cost starts within one unit. So a part holds less than one unit plus one transaction: with half the platform's limits
-- as the unit, a part stays within them while no transaction is itself over half of either (maxTransaction*).
WITH taken AS (
    SELECT t.{{LedgerTransaction.transactionId}} AS tx,
           CAST(a.{{LedgerAccount.accountCode}} AS text) AS acct,
           e.{{LedgerEntry.direction}} AS side,
           CAST(CASE WHEN a.{{LedgerAccount.accountCode}} = '2110' THEN e.{{LedgerEntry.dimension1}} END AS text) AS party,
           CAST(t.{{LedgerTransaction.sourceEntity}} AS text) AS src,
           CAST(t.{{LedgerTransaction.sourceId}} AS text) AS src_id,
           CAST(t.{{LedgerTransaction.reference}} AS text) AS ref
    FROM {{LedgerTransaction}} t
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE t.{{LedgerTransaction.bookingTime}} < :to
      AND t.{{LedgerTransaction.createdTime}} <= :knownAt
      AND (CAST(:previousTo AS timestamptz) IS NULL
           OR t.{{LedgerTransaction.bookingTime}} >= :previousTo
           OR t.{{LedgerTransaction.createdTime}} > :previousKnownAt)
),
detail AS (
    SELECT tx,
           260 + octet_length(acct) + 2 * octet_length(coalesce(party, '')) + 2 * octet_length(coalesce(src, ''))
               + 2 * octet_length(coalesce(src_id, '')) + 2 * octet_length(coalesce(ref, '')) AS row_bytes
    FROM taken
    GROUP BY tx, acct, side, party, src, src_id, ref
),
per_tx AS (
    SELECT tx, count(*) AS n, sum(row_bytes) AS bytes
    FROM detail
    GROUP BY tx
),
costed AS (
    SELECT tx, n, bytes,
           greatest(n / CAST(:partRows AS numeric), bytes / CAST(:partBytes AS numeric)) AS cost
    FROM per_tx
),
placed AS (
    SELECT tx, n, bytes,
           floor(sum(cost) OVER (ORDER BY tx ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) - cost) AS slot
    FROM costed
)
SELECT
    CAST(row_number() OVER (ORDER BY slot) AS numeric(9, 0)) AS partNo,
    (array_agg(tx ORDER BY tx))[1]      AS firstTransaction,
    (array_agg(tx ORDER BY tx DESC))[1] AS lastTransaction,
    count(*)   AS transactions,
    sum(n)     AS rowCount,
    sum(bytes) AS bytes,
    max(n)     AS maxTransactionRows,
    max(bytes) AS maxTransactionBytes
FROM placed
GROUP BY slot
