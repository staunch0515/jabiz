/*---
id: finance.gl.trial_balance
description: >-
  Every account's debit or credit balance over the entries posted up to a date (FIN-RP-001 basis), with its type,
  normal balance and statement line; a summary account shows the total of the accounts under it (FIN-GL-003). The
  adjustment period 13 of the fiscal year holding the day counts unless left out (earlier years' always count: their
  balances carry forward); the books as recorded up to a time, if given.
entities: [LedgerAccount, LedgerTransaction, LedgerEntry, FinAccount, FinPosting, FinPeriod]
params:
  through:     { like: FinPosting.postingDate, required: true, description: entries posted on or before this day count }
  adjustments: { kind: { type: bool }, description: "whether period 13 of the day's fiscal year counts; yes when not given" }
  knownAt:     { like: LedgerTransaction.bookingTime, description: "if given, the books as recorded at this time" }
  closingEntries: { kind: { type: bool }, description: "whether the closing entry of the day's fiscal year counts; yes when not given (FIN-RP-001: before or after closing)" }
results:
  accountCode:   { from: LedgerAccount.accountCode }
  accountName:   { from: LedgerAccount.accountName }
  financialType: { from: FinAccount.financialType }
  normalBalance: { from: FinAccount.normalBalance }
  statementLine: { from: FinAccount.statementLine }
  parentCode:    { from: LedgerAccount.accountCode }
  summary:       { from: LedgerAccount.summary }
  level:         { kind: { type: numeric, precision: 9, scale: 0 } }
  debit:         { from: LedgerEntry.amount }
  credit:        { from: LedgerEntry.amount }
  balance:       { from: LedgerEntry.amount }
list:
  filters: [accountCode, financialType, statementLine, parentCode, summary, level]
  sorts:   [accountCode, balance, level]
  defaultSort: { field: accountCode, asc: true }
  key: [accountCode]
permissions: [ledger.read]
timeSlice: { knownAt: knownAt }
report:
  period: { to: through }
---*/
WITH RECURSIVE tree (acct_key, ancestor_id) AS (
    SELECT a.{{LedgerAccount.accountId}}, a.{{LedgerAccount.accountId}}
    FROM {{LedgerAccount}} a
    UNION
    SELECT t.acct_key, p.{{LedgerAccount.parentId}}
    FROM tree t
    JOIN {{LedgerAccount}} p ON p.{{LedgerAccount.accountId}} = t.ancestor_id
    WHERE p.{{LedgerAccount.parentId}} IS NOT NULL
),
moves AS (
    SELECT e.{{LedgerEntry.accountId}} AS entry_account,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                ELSE -e.{{LedgerEntry.amount}} END AS signed_amount
    FROM {{LedgerEntry}} e
    JOIN {{LedgerTransaction}} t
      ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPosting}} fp
      ON fp.{{FinPosting.transactionId}} = t.{{LedgerTransaction.transactionId}}
    WHERE fp.{{FinPosting.postingDate}} <= :through
      AND (COALESCE(CAST(:adjustments AS boolean), true) OR fp.{{FinPosting.periodNo}} <> 13
           OR fp.{{FinPosting.fiscalYear}} <> (
               SELECT MAX(yp.{{FinPeriod.fiscalYear}}) FROM {{FinPeriod}} yp
               WHERE yp.{{FinPeriod.startDate}} <= :through AND yp.{{FinPeriod.endDate}} >= :through))
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
      -- CLS: the general ledger source of the year-end closing entries (JournalEntities.postingSource). Earlier
      -- years' closings always count: they are what carries income into retained earnings.
      AND (COALESCE(CAST(:closingEntries AS boolean), true) OR fp.{{FinPosting.source}} <> 'CLS'
           OR fp.{{FinPosting.fiscalYear}} <> (
               SELECT MAX(cy.{{FinPeriod.fiscalYear}}) FROM {{FinPeriod}} cy
               WHERE cy.{{FinPeriod.startDate}} <= :through AND cy.{{FinPeriod.endDate}} >= :through))
),
totals AS (
    SELECT tree.ancestor_id AS acct_key, SUM(m.signed_amount) AS net
    FROM moves m
    JOIN tree ON tree.acct_key = m.entry_account
    GROUP BY tree.ancestor_id
),
depth AS (
    SELECT acct_key, COUNT(*) - 1 AS depth_level FROM tree GROUP BY acct_key
)
SELECT
    a.{{LedgerAccount.accountCode}}   AS accountCode,
    a.{{LedgerAccount.accountName}}   AS accountName,
    f.{{FinAccount.financialType}}    AS financialType,
    f.{{FinAccount.normalBalance}}    AS normalBalance,
    f.{{FinAccount.statementLine}}    AS statementLine,
    p.{{LedgerAccount.accountCode}}   AS parentCode,
    COALESCE(a.{{LedgerAccount.summary}}, false) AS summary,
    d.depth_level                     AS level,
    CASE WHEN COALESCE(s.net, 0) > 0 THEN s.net ELSE 0 END  AS debit,
    CASE WHEN COALESCE(s.net, 0) < 0 THEN -s.net ELSE 0 END AS credit,
    COALESCE(s.net, 0)                AS balance
FROM {{LedgerAccount}} a
JOIN {{FinAccount}} f ON f.{{FinAccount.ledgerAccountId}} = a.{{LedgerAccount.accountId}}
LEFT JOIN {{LedgerAccount}} p ON p.{{LedgerAccount.accountId}} = a.{{LedgerAccount.parentId}}
JOIN depth d ON d.acct_key = a.{{LedgerAccount.accountId}}
LEFT JOIN totals s ON s.acct_key = a.{{LedgerAccount.accountId}}
