/*---
id: finance.ar.allowance_suggestion
description: >-
  The allowance for credit losses the aging suggests on a day (FIN-AR-011, ASC 326 simplified): what was open in
  US dollars in each bucket by due date (current, 1-30, 31-60, 61-90, over 90 days) times the bucket's loss rate from
  the receivables settings, the suggested allowance, the allowance account's balance on that day and the adjustment
  a journal entry would make (positive: add to the allowance). Buckets without a rate suggest nothing.
entities: [FinInvoice, FinApplication, FinArSettings, LedgerAccount, LedgerTransaction, LedgerEntry, FinPosting]
params:
  onDate:  { like: FinApplication.applicationDate, required: true, description: "the day the allowance is for, at its end" }
  knownAt: { like: LedgerTransaction.bookingTime, description: "if given, the books as recorded at this time" }
results:
  seq:        { kind: { type: numeric, precision: 1, scale: 0 } }
  bucket:     { kind: { type: text, maxLength: 12 } }
  openAmount: { from: FinInvoice.openAmountUsd }
  lossRate:   { from: FinArSettings.lossRateCurrent }
  suggested:  { from: FinInvoice.openAmountUsd }
  existing:   { from: FinInvoice.openAmountUsd }
  adjustment: { from: FinInvoice.openAmountUsd }
list:
  filters: [bucket]
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [seq]
permissions: [fin.ar.read, ledger.read]
timeSlice: { knownAt: knownAt }
report:
  period: { to: onDate }
---*/
WITH docs AS (
    SELECT i.{{FinInvoice.invoiceId}} AS doc_key, i.{{FinInvoice.kind}} AS doc_kind,
           COALESCE(i.{{FinInvoice.dueDate}}, i.{{FinInvoice.invoiceDate}}) AS due_on,
           i.{{FinInvoice.totalUsd}} AS doc_total_usd
    FROM {{FinInvoice}} i
    WHERE i.{{FinInvoice.status}} IN ('POSTED', 'VOID', 'WRITTEN_OFF')
      AND COALESCE(i.{{FinInvoice.postingDate}}, i.{{FinInvoice.invoiceDate}}) <= :onDate
      AND (i.{{FinInvoice.voidDate}} IS NULL OR i.{{FinInvoice.voidDate}} > :onDate)
),
cleared AS (
    SELECT a.{{FinApplication.invoiceId}} AS doc_key,
           SUM(a.{{FinApplication.amountUsd}} + COALESCE(a.{{FinApplication.discount}}, 0)) AS done_usd
    FROM {{FinApplication}} a
    WHERE a.{{FinApplication.applicationDate}} <= :onDate
    GROUP BY a.{{FinApplication.invoiceId}}
    UNION ALL
    SELECT CAST(a.{{FinApplication.sourceId}} AS uuid),
           SUM(COALESCE(a.{{FinApplication.sourceAmountUsd}}, a.{{FinApplication.amountUsd}}))
    FROM {{FinApplication}} a
    WHERE a.{{FinApplication.sourceKind}} = 'CREDIT_MEMO' AND a.{{FinApplication.applicationDate}} <= :onDate
    GROUP BY a.{{FinApplication.sourceId}}
),
-- Summed once a document and joined (F11c), as in the agings.
cleared_by AS (
    SELECT doc_key, SUM(done_usd) AS done_usd FROM cleared GROUP BY doc_key
),
aged AS (
    SELECT CASE WHEN d.doc_kind = 'CREDIT_MEMO' THEN -1 ELSE 1 END * (d.doc_total_usd - COALESCE(c.done_usd, 0))
               AS open_usd,
           CAST(:onDate AS date) - d.due_on AS age
    FROM docs d
    LEFT JOIN cleared_by c ON c.doc_key = d.doc_key
),
buckets (seq, label, low, high) AS (
    VALUES (0, 'Current', -100000, 0), (1, '1-30', 1, 30), (2, '31-60', 31, 60), (3, '61-90', 61, 90),
           (4, 'Over 90', 91, 100000)
),
settings AS (
    SELECT s.{{FinArSettings.allowanceAccount}} AS allowance,
           s.{{FinArSettings.lossRateCurrent}} AS r0, s.{{FinArSettings.lossRate1}} AS r1,
           s.{{FinArSettings.lossRate2}} AS r2, s.{{FinArSettings.lossRate3}} AS r3,
           s.{{FinArSettings.lossRateOver}} AS r4
    FROM {{FinArSettings}} s
    WHERE s.{{FinArSettings.settingsKey}} = 'AR'
),
per_bucket AS (
    SELECT b.seq, b.label,
           COALESCE((SELECT SUM(a.open_usd) FROM aged a WHERE a.age BETWEEN b.low AND b.high), 0) AS open_usd,
           CASE b.seq WHEN 0 THEN s.r0 WHEN 1 THEN s.r1 WHEN 2 THEN s.r2 WHEN 3 THEN s.r3 ELSE s.r4 END AS rate
    FROM buckets b
    CROSS JOIN settings s
),
existing AS (
    -- The allowance account's credit balance on the day.
    SELECT COALESCE(SUM(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
                             ELSE -e.{{LedgerEntry.amount}} END), 0) AS bal
    FROM {{LedgerEntry}} e
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = (SELECT allowance FROM settings)
      AND fp.{{FinPosting.postingDate}} <= :onDate
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
)
SELECT p.seq AS seq, p.label AS bucket, p.open_usd AS openAmount, p.rate AS lossRate,
       ROUND(p.open_usd * COALESCE(p.rate, 0) / 100, 2) AS suggested,
       CAST(NULL AS numeric) AS existing, CAST(NULL AS numeric) AS adjustment
FROM per_bucket p
UNION ALL
SELECT 9, 'Total', SUM(p.open_usd), NULL, SUM(ROUND(p.open_usd * COALESCE(p.rate, 0) / 100, 2)),
       MAX(x.bal), SUM(ROUND(p.open_usd * COALESCE(p.rate, 0) / 100, 2)) - MAX(x.bal)
FROM per_bucket p CROSS JOIN existing x
