/*---
id: finance.migration.reconciliation
description: >-
  The migration's control totals (FIN-DI-002, FIN-PC-002): for every account, the opening entry as loaded from the
  source against the general ledger's balance at the opening date and the difference, which is 0.00 when the
  migration is complete; the debit and credit totals; and every recorded decision on the legacy data, with who
  decided and when (FIN-DI-003). The subledgers' opening items join it with their phases.
entities: [FinJournal, FinJournalLine, LedgerAccount, LedgerTransaction, LedgerEntry, FinPosting, FinMigrationDecision]
params:
  knownAt: { like: LedgerTransaction.bookingTime, description: "if given, the books as recorded at this time" }
results:
  seq:          { kind: { type: numeric, precision: 1, scale: 0 } }
  section:      { kind: { type: text, maxLength: 10 } }
  item:         { kind: { type: text, maxLength: 210 } }
  description:  { kind: { type: text, maxLength: 500 } }
  sourceAmount: { from: LedgerEntry.amount }
  ledgerAmount: { from: LedgerEntry.amount }
  difference:   { from: LedgerEntry.amount }
  decidedBy:    { from: FinMigrationDecision.decidedBy }
  decidedAt:    { from: FinMigrationDecision.decidedAt }
list:
  filters: [section, item]
  sorts:   [seq, item, difference]
  defaultSort: { field: seq, asc: true }
  key: [seq, item]
permissions: [fin.journal.read, ledger.read]
timeSlice: { knownAt: knownAt }
report:
  landscape: true
---*/
WITH opening AS (
    SELECT j.{{FinJournal.journalId}} AS journal_key, j.{{FinJournal.postingDate}} AS opened_on
    FROM {{FinJournal}} j
    WHERE j.{{FinJournal.source}} = 'OPENING'
),
source_lines AS (
    SELECT l.{{FinJournalLine.accountCode}} AS code,
           COALESCE(l.{{FinJournalLine.debit}}, 0)  AS dr,
           COALESCE(l.{{FinJournalLine.credit}}, 0) AS cr
    FROM {{FinJournalLine}} l
    JOIN opening o ON o.journal_key = l.{{FinJournalLine.journalId}}
),
ledger_lines AS (
    SELECT a.{{LedgerAccount.accountCode}} AS code,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END AS dr,
           CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END AS cr
    FROM {{LedgerEntry}} e
    JOIN {{LedgerTransaction}} t
      ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPosting}} fp
      ON fp.{{FinPosting.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE fp.{{FinPosting.postingDate}} <= (SELECT MIN(opened_on) FROM opening)
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
),
source_net AS (
    SELECT code, SUM(dr - cr) AS net FROM source_lines GROUP BY code
),
ledger_net AS (
    SELECT code, SUM(dr - cr) AS net FROM ledger_lines GROUP BY code
),
codes AS (
    SELECT code FROM source_net UNION SELECT code FROM ledger_net
)
SELECT 1 AS seq, 'ACCOUNT' AS section, c.code AS item, a.{{LedgerAccount.accountName}} AS description,
       COALESCE(s.net, 0) AS sourceAmount, COALESCE(g.net, 0) AS ledgerAmount,
       COALESCE(g.net, 0) - COALESCE(s.net, 0) AS difference,
       CAST(NULL AS varchar) AS decidedBy, CAST(NULL AS timestamptz) AS decidedAt
FROM codes c
LEFT JOIN source_net s ON s.code = c.code
LEFT JOIN ledger_net g ON g.code = c.code
LEFT JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountCode}} = c.code
UNION ALL
SELECT 2, 'TOTAL', 'Debits', 'Total debits',
       (SELECT COALESCE(SUM(dr), 0) FROM source_lines), (SELECT COALESCE(SUM(dr), 0) FROM ledger_lines),
       (SELECT COALESCE(SUM(dr), 0) FROM ledger_lines) - (SELECT COALESCE(SUM(dr), 0) FROM source_lines),
       NULL, NULL
UNION ALL
SELECT 2, 'TOTAL', 'Credits', 'Total credits',
       (SELECT COALESCE(SUM(cr), 0) FROM source_lines), (SELECT COALESCE(SUM(cr), 0) FROM ledger_lines),
       (SELECT COALESCE(SUM(cr), 0) FROM ledger_lines) - (SELECT COALESCE(SUM(cr), 0) FROM source_lines),
       NULL, NULL
UNION ALL
SELECT 3, 'DECISION', d.{{FinMigrationDecision.kind}} || ' ' || d.{{FinMigrationDecision.legacyValue}} || ' -> '
       || d.{{FinMigrationDecision.decidedValue}}, d.{{FinMigrationDecision.reason}}, NULL, NULL, NULL,
       d.{{FinMigrationDecision.decidedBy}}, d.{{FinMigrationDecision.decidedAt}}
FROM {{FinMigrationDecision}} d
