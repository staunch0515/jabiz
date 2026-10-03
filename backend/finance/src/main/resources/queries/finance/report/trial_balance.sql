/*---
id: finance.report.trial_balance
description: >-
  The trial balance for a period or a date range (FIN-RP-001): each account's opening balance before the range, its
  debits and credits in the range and its closing balance, debit positive, with the closing balance also as a debit or
  a credit; summary accounts total the accounts under them (FIN-GL-003); one department or location if asked. The range
  starts at the regular period holding the last day unless given. Period 13 of that day's fiscal year counts unless left
  out, and so do its closing entries (before or after closing); earlier years' always count, their balances carrying
  forward. The books as recorded up to a time, if given. A whole period is read from its latest balance snapshot and the
  entries recorded after it (ROADMAP F9 decision D1); the result is that of summing the entries (but for an entry
  committed after a close that began before it was recorded: ROADMAP F9a known limitations).
entities: [LedgerAccount, LedgerTransaction, LedgerEntry, FinAccount, FinPosting, FinPeriod, FinPeriodBalance]
params:
  from:           { like: FinPosting.postingDate, description: "the range's first day; the start of the regular period holding the last day when not given" }
  through:        { like: FinPosting.postingDate, required: true, description: the range's last day }
  adjustments:    { kind: { type: bool }, description: "whether period 13 of the last day's fiscal year counts; yes when not given" }
  closingEntries: { kind: { type: bool }, description: "whether the closing entry of the last day's fiscal year counts; yes when not given" }
  department:     { like: LedgerEntry.dimension1, description: "only this department's entries" }
  location:       { like: LedgerEntry.dimension2, description: "only this location's entries" }
  knownAt:        { like: LedgerTransaction.bookingTime, description: "if given, the books as recorded at this time" }
results:
  accountCode:   { from: LedgerAccount.accountCode }
  accountName:   { from: LedgerAccount.accountName }
  financialType: { from: FinAccount.financialType }
  normalBalance: { from: FinAccount.normalBalance }
  statementLine: { from: FinAccount.statementLine }
  parentCode:    { from: LedgerAccount.accountCode }
  summary:       { from: LedgerAccount.summary }
  level:         { kind: { type: numeric, precision: 9, scale: 0 } }
  opening:       { from: LedgerEntry.amount }
  debit:         { from: LedgerEntry.amount }
  credit:        { from: LedgerEntry.amount }
  closing:       { from: LedgerEntry.amount }
  closingDebit:  { from: LedgerEntry.amount }
  closingCredit: { from: LedgerEntry.amount }
list:
  filters: [accountCode, financialType, statementLine, parentCode, summary, level]
  sorts:   [accountCode, closing, level]
  defaultSort: { field: accountCode, asc: true }
  key: [accountCode]
permissions: [ledger.read]
timeSlice: { knownAt: knownAt }
report:
  period: { from: from, to: through }
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
bounds AS (
    SELECT COALESCE(CAST(:from AS date),
               (SELECT MAX(rp.{{FinPeriod.startDate}}) FROM {{FinPeriod}} rp
                WHERE rp.{{FinPeriod.startDate}} <= :through AND rp.{{FinPeriod.endDate}} >= :through
                  AND NOT COALESCE(rp.{{FinPeriod.adjustment}}, false)),
               CAST(:through AS date)) AS first_day,
           (SELECT MAX(yp.{{FinPeriod.fiscalYear}}) FROM {{FinPeriod}} yp
            WHERE yp.{{FinPeriod.startDate}} <= :through AND yp.{{FinPeriod.endDate}} >= :through) AS last_year
),
-- Each period that may hold counted entries: whether all of it is before the range ('O') or inside it ('R'), else
-- partly so (read entry by entry); whether it is the last year's period 13 left out, or read without its closing entry.
periods AS (
    SELECT p.{{FinPeriod.periodKey}} AS pkey,
           CASE WHEN p.{{FinPeriod.endDate}} < b.first_day THEN 'O'
                WHEN p.{{FinPeriod.startDate}} >= b.first_day AND p.{{FinPeriod.endDate}} <= :through THEN 'R'
           END AS whole,
           COALESCE(p.{{FinPeriod.adjustment}}, false) AND p.{{FinPeriod.fiscalYear}} = b.last_year
               AND NOT COALESCE(CAST(:adjustments AS boolean), true) AS left_out,
           COALESCE(p.{{FinPeriod.adjustment}}, false) AND p.{{FinPeriod.fiscalYear}} = b.last_year
               AND NOT COALESCE(CAST(:closingEntries AS boolean), true) AS without_closing
    FROM {{FinPeriod}} p CROSS JOIN bounds b
    WHERE p.{{FinPeriod.startDate}} <= :through
),
snapshots AS (
    SELECT s.{{FinPeriodBalance.periodKey}} AS pkey, MAX(s.{{FinPeriodBalance.countedTo}}) AS counted
    FROM {{FinPeriodBalance}} s
    JOIN periods pr ON pr.pkey = s.{{FinPeriodBalance.periodKey}}
    WHERE pr.whole IS NOT NULL AND NOT pr.left_out AND NOT pr.without_closing
      AND (CAST(:knownAt AS timestamptz) IS NULL OR s.{{FinPeriodBalance.countedTo}} <= :knownAt)
    GROUP BY s.{{FinPeriodBalance.periodKey}}
),
moves AS (
    SELECT a.{{LedgerAccount.accountId}} AS entry_account, pr.whole AS side,
           s.{{FinPeriodBalance.debit}} AS dr, s.{{FinPeriodBalance.credit}} AS cr
    FROM {{FinPeriodBalance}} s
    JOIN snapshots sn ON sn.pkey = s.{{FinPeriodBalance.periodKey}} AND sn.counted = s.{{FinPeriodBalance.countedTo}}
    JOIN periods pr ON pr.pkey = s.{{FinPeriodBalance.periodKey}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountCode}} = s.{{FinPeriodBalance.accountCode}}
    WHERE (CAST(:department AS text) IS NULL OR s.{{FinPeriodBalance.department}} = :department)
      AND (CAST(:location AS text) IS NULL OR s.{{FinPeriodBalance.location}} = :location)
    UNION ALL
    SELECT e.{{LedgerEntry.accountId}},
           CASE WHEN fp.{{FinPosting.postingDate}} < b.first_day THEN 'O' ELSE 'R' END,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END,
           CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}} ELSE 0 END
    FROM {{LedgerEntry}} e
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN periods pr ON pr.pkey = fp.{{FinPosting.periodKey}}
    CROSS JOIN bounds b
    LEFT JOIN snapshots sn ON sn.pkey = fp.{{FinPosting.periodKey}}
    WHERE fp.{{FinPosting.postingDate}} <= :through
      AND NOT pr.left_out
      -- CLS: the general ledger source of the year-end closing entries (JournalEntities.postingSource).
      AND (NOT pr.without_closing OR fp.{{FinPosting.source}} <> 'CLS')
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
      -- After the period's snapshot only: it counts the rest.
      AND (sn.counted IS NULL OR t.{{LedgerTransaction.createdTime}} > sn.counted)
      AND (CAST(:department AS text) IS NULL OR e.{{LedgerEntry.dimension1}} = :department)
      AND (CAST(:location AS text) IS NULL OR e.{{LedgerEntry.dimension2}} = :location)
),
totals AS (
    SELECT tree.ancestor_id AS acct_key,
           SUM(CASE WHEN m.side = 'O' THEN m.dr - m.cr ELSE 0 END) AS opening_net,
           SUM(CASE WHEN m.side = 'R' THEN m.dr ELSE 0 END) AS range_dr,
           SUM(CASE WHEN m.side = 'R' THEN m.cr ELSE 0 END) AS range_cr
    FROM moves m
    JOIN tree ON tree.acct_key = m.entry_account
    GROUP BY tree.ancestor_id
),
depth AS (
    SELECT acct_key, COUNT(*) - 1 AS depth_level FROM tree GROUP BY acct_key
),
account_rows AS (
    SELECT a.{{LedgerAccount.accountId}} AS acct_key,
           COALESCE(s.opening_net, 0) AS opening_net,
           COALESCE(s.range_dr, 0) AS range_dr,
           COALESCE(s.range_cr, 0) AS range_cr,
           COALESCE(s.opening_net, 0) + COALESCE(s.range_dr, 0) - COALESCE(s.range_cr, 0) AS closing_net
    FROM {{LedgerAccount}} a
    LEFT JOIN totals s ON s.acct_key = a.{{LedgerAccount.accountId}}
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
    r.opening_net                     AS opening,
    r.range_dr                        AS debit,
    r.range_cr                        AS credit,
    r.closing_net                     AS closing,
    CASE WHEN r.closing_net > 0 THEN r.closing_net ELSE 0 END  AS closingDebit,
    CASE WHEN r.closing_net < 0 THEN -r.closing_net ELSE 0 END AS closingCredit
FROM {{LedgerAccount}} a
JOIN {{FinAccount}} f ON f.{{FinAccount.ledgerAccountId}} = a.{{LedgerAccount.accountId}}
LEFT JOIN {{LedgerAccount}} p ON p.{{LedgerAccount.accountId}} = a.{{LedgerAccount.parentId}}
JOIN depth d ON d.acct_key = a.{{LedgerAccount.accountId}}
JOIN account_rows r ON r.acct_key = a.{{LedgerAccount.accountId}}
