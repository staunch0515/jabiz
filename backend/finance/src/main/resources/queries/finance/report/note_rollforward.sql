/*---
id: finance.report.note_rollforward
description: >-
  A note schedule (FIN-RP-012; ROADMAP F9 decision D11): the roll-forward over a range of the accounts the report
  settings name for the note (RECEIVABLES: receivables and their allowance; ACCRUED: accrued liabilities; DEBT: lines
  of credit and notes), each account's balance at the start, its movements by where they came from (receivables,
  payables, bank, fixed assets, manual journals, imports, revaluation) and its balance at the end, then the same for
  all of them together. Amounts in the account's normal balance (a liability's credit positive). The range starts at
  the regular period holding the last day unless given; closing entries never count. Property and equipment and
  foreign-exchange gains and losses have their own schedules (finance.fa.roll_forward, finance.fx.gains_losses).
entities: [LedgerAccount, LedgerTransaction, LedgerEntry, FinAccount, FinPosting, FinPeriod, FinReportSettings]
params:
  note:    { kind: { type: text, maxLength: 12 }, required: true, description: "RECEIVABLES, ACCRUED or DEBT" }
  from:    { like: FinPosting.postingDate, description: "the range's first day; the start of the regular period holding the last day when not given" }
  through: { like: FinPosting.postingDate, required: true, description: the range's last day }
  knownAt: { like: LedgerTransaction.bookingTime, description: "if given, the books as recorded at this time" }
results:
  seq:         { kind: { type: numeric, precision: 9, scale: 0 } }
  lineCode:    { kind: { type: text, maxLength: 60 } }
  accountCode: { from: LedgerAccount.accountCode }
  accountName: { from: LedgerAccount.accountName }
  movement:    { kind: { type: text, maxLength: 10 } }
  label:       { kind: { type: text, maxLength: 100 } }
  amount:      { from: LedgerEntry.amount }
list:
  filters: [accountCode, movement]
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [lineCode]
permissions: [ledger.read]
timeSlice: { knownAt: knownAt }
report:
  period: { from: from, to: through }
---*/
WITH bounds AS (
    SELECT COALESCE(CAST(:from AS date),
               (SELECT MAX(rp.{{FinPeriod.startDate}}) FROM {{FinPeriod}} rp
                WHERE rp.{{FinPeriod.startDate}} <= :through AND rp.{{FinPeriod.endDate}} >= :through
                  AND NOT COALESCE(rp.{{FinPeriod.adjustment}}, false)),
               CAST(:through AS date)) AS first_day
),
ranges AS (
    SELECT CASE CAST(:note AS text)
               WHEN 'RECEIVABLES' THEN rs.{{FinReportSettings.receivablesAccounts}}
               WHEN 'ACCRUED' THEN rs.{{FinReportSettings.accruedAccounts}}
               WHEN 'DEBT' THEN rs.{{FinReportSettings.debtAccounts}} END AS accounts
    FROM {{FinReportSettings}} rs
    WHERE rs.{{FinReportSettings.settingsKey}} = 'REPORTS'
),
accts AS (
    SELECT a.{{LedgerAccount.accountId}} AS acct_key, a.{{LedgerAccount.accountCode}} AS code,
           a.{{LedgerAccount.accountName}} AS acct_name,
           CASE WHEN f.{{FinAccount.normalBalance}} = 'CREDIT' THEN -1 ELSE 1 END AS shown
    FROM {{LedgerAccount}} a
    JOIN {{FinAccount}} f ON f.{{FinAccount.ledgerAccountId}} = a.{{LedgerAccount.accountId}}
    WHERE NOT COALESCE(a.{{LedgerAccount.summary}}, false)
      AND EXISTS (SELECT 1 FROM ranges rg
                  CROSS JOIN UNNEST(STRING_TO_ARRAY(REPLACE(rg.accounts, ' ', ''), ',')) AS r (part)
                  WHERE a.{{LedgerAccount.accountCode}} COLLATE "C" BETWEEN SPLIT_PART(r.part, '-', 1)
                        AND COALESCE(NULLIF(SPLIT_PART(r.part, '-', 2), ''), SPLIT_PART(r.part, '-', 1)))
),
-- The accounts' entries up to the last day, closing entries left out: before the range as the opening, in it by
-- where they came from (a revaluation run's postings as FX, whatever their series).
lines AS (
    SELECT e.{{LedgerEntry.accountId}} AS acct_key,
           CASE WHEN fp.{{FinPosting.postingDate}} < b.first_day THEN 'OPENING'
                WHEN fp.{{FinPosting.sourceEntity}} = 'FinFxRevaluationRun' THEN 'FX'
                ELSE fp.{{FinPosting.source}} END AS movement,
           CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                ELSE -e.{{LedgerEntry.amount}} END AS net
    FROM {{LedgerEntry}} e
    JOIN accts ac ON ac.acct_key = e.{{LedgerEntry.accountId}}
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = t.{{LedgerTransaction.transactionId}}
    CROSS JOIN bounds b
    WHERE fp.{{FinPosting.postingDate}} <= :through
      AND fp.{{FinPosting.source}} <> 'CLS'
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
),
movements (movement, label, ord) AS (
    VALUES ('OPENING', 'Balance at the start', 0), ('OPN', 'Opening balances', 1), ('AR', 'Receivables', 2),
           ('AP', 'Payables', 3), ('BK', 'Bank', 4), ('FA', 'Fixed assets', 5), ('MAN', 'Manual journals', 6),
           ('IMP', 'Imports', 7), ('FX', 'Revaluation', 8), ('CLOSING', 'Balance at the end', 9)
),
per_account AS (
    SELECT ac.code, ac.acct_name, ac.shown, mv.movement, mv.label, mv.ord,
           CASE WHEN mv.movement = 'CLOSING' THEN COALESCE(SUM(l.net), 0)
                ELSE COALESCE(SUM(l.net) FILTER (WHERE l.movement = mv.movement), 0) END AS net
    FROM accts ac
    CROSS JOIN movements mv
    LEFT JOIN lines l ON l.acct_key = ac.acct_key
    GROUP BY ac.code, ac.acct_name, ac.shown, mv.movement, mv.label, mv.ord
)
-- Each account in its own normal balance, the start and the end always and a movement only when there is one; the
-- total in the normal balance of the note's first account (receivables net of their allowance).
SELECT DENSE_RANK() OVER (ORDER BY code) * 100 + ord AS seq, code || '.' || movement AS lineCode,
       code AS accountCode, acct_name AS accountName, movement, label, shown * net AS amount
FROM per_account
WHERE movement IN ('OPENING', 'CLOSING') OR net <> 0
UNION ALL
SELECT 100000000 + pa.ord, 'TOTAL.' || pa.movement, NULL, NULL, pa.movement, pa.label,
       (SELECT f.shown FROM accts f ORDER BY f.code COLLATE "C" LIMIT 1) * SUM(pa.net)
FROM per_account pa
GROUP BY pa.movement, pa.label, pa.ord
HAVING pa.movement IN ('OPENING', 'CLOSING') OR SUM(pa.net) <> 0
