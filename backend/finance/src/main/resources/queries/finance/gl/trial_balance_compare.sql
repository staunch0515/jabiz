/*---
id: finance.gl.trial_balance_compare
description: >-
  The trial balance on a day as known at two times (FIN-RP-020): each account's balance, debit positive, over the
  entries posted by that day and recorded by the earlier time, then by the later one (now when not given), and the
  difference — the effect of the entries recorded in between, such as a back-dated correction. Period 13 of the day's
  fiscal year counts unless left out; only the accounts that differ if asked.
entities: [LedgerAccount, LedgerTransaction, LedgerEntry, FinAccount, FinPosting, FinPeriod]
params:
  through:     { like: FinPosting.postingDate, required: true, description: entries posted on or before this day count }
  earlier:     { like: LedgerTransaction.bookingTime, required: true, description: "the first view: the books as recorded at this time" }
  later:       { like: LedgerTransaction.bookingTime, description: "the second view: the books as recorded at this time; now when not given" }
  adjustments: { kind: { type: bool }, description: "whether period 13 of the day's fiscal year counts; yes when not given" }
  changedOnly: { kind: { type: bool }, description: "only the accounts whose balances differ; no when not given" }
results:
  accountCode:   { from: LedgerAccount.accountCode }
  accountName:   { from: LedgerAccount.accountName }
  financialType: { from: FinAccount.financialType }
  earlierBalance: { from: LedgerEntry.amount }
  laterBalance:   { from: LedgerEntry.amount }
  difference:     { from: LedgerEntry.amount }
list:
  filters: [accountCode, financialType]
  sorts:   [accountCode, difference]
  defaultSort: { field: accountCode, asc: true }
  key: [accountCode]
permissions: [ledger.read]
report:
  period: { to: through }
---*/
WITH moves AS (
    SELECT e.{{LedgerEntry.accountId}} AS entry_account, t.{{LedgerTransaction.createdTime}} AS recorded,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                ELSE -e.{{LedgerEntry.amount}} END AS signed_amount
    FROM {{LedgerEntry}} e
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = t.{{LedgerTransaction.transactionId}}
    WHERE fp.{{FinPosting.postingDate}} <= :through
      AND (COALESCE(CAST(:adjustments AS boolean), true) OR fp.{{FinPosting.periodNo}} <> 13
           OR fp.{{FinPosting.fiscalYear}} <> (
               SELECT MAX(yp.{{FinPeriod.fiscalYear}}) FROM {{FinPeriod}} yp
               WHERE yp.{{FinPeriod.startDate}} <= :through AND yp.{{FinPeriod.endDate}} >= :through))
      AND (CAST(:later AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :later)
),
totals AS (
    SELECT entry_account,
           SUM(CASE WHEN recorded <= :earlier THEN signed_amount ELSE 0 END) AS first_net,
           SUM(signed_amount) AS second_net
    FROM moves
    GROUP BY entry_account
)
SELECT a.{{LedgerAccount.accountCode}} AS accountCode,
       a.{{LedgerAccount.accountName}} AS accountName,
       f.{{FinAccount.financialType}}  AS financialType,
       COALESCE(s.first_net, 0)        AS earlierBalance,
       COALESCE(s.second_net, 0)       AS laterBalance,
       COALESCE(s.second_net, 0) - COALESCE(s.first_net, 0) AS difference
FROM {{LedgerAccount}} a
JOIN {{FinAccount}} f ON f.{{FinAccount.ledgerAccountId}} = a.{{LedgerAccount.accountId}}
LEFT JOIN totals s ON s.entry_account = a.{{LedgerAccount.accountId}}
WHERE NOT COALESCE(a.{{LedgerAccount.summary}}, false)
  AND (NOT COALESCE(CAST(:changedOnly AS boolean), false)
       OR COALESCE(s.second_net, 0) <> COALESCE(s.first_net, 0))
