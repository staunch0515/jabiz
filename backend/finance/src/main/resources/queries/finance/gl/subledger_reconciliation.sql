/*---
id: finance.gl.subledger_reconciliation
description: >-
  Each control account against its subledger on a day (FIN-RP-007): the receivables aging's US dollar total against
  the AR control accounts, the payables aging's against the AP ones, the asset register's cost and accumulated
  depreciation against the FA cost and accumulated depreciation accounts, each with the difference. The subledgers are
  counted as the agings and the register count them (finance.ar.aging, finance.ap.aging, finance.fa.register); the
  accounts as the close checks do, without period 13 of the day's fiscal year. Amounts what is owed or held, positive.
entities: [FinInvoice, FinApplication, FinBill, FinApApplication, FinFxRevaluationRun, FinFxRevaluationLine, FinAsset,
  FinAssetClass, FinDepreciationRun, FinDepreciationLine, FinAssetDisposal, FinAccount, FinPosting, FinPeriod,
  LedgerAccount, LedgerTransaction, LedgerEntry]
params:
  asOf:    { like: FinPosting.postingDate, required: true, description: "the day, at its end" }
  knownAt: { like: LedgerTransaction.bookingTime, description: "if given, the books as recorded at this time" }
results:
  controlClass: { from: FinAccount.controlClass }
  name:         { kind: { type: text, maxLength: 100 } }
  accounts:     { kind: { type: text, maxLength: 500 } }
  subledger:    { from: LedgerEntry.amount }
  ledger:       { from: LedgerEntry.amount }
  difference:   { from: LedgerEntry.amount }
list:
  sorts:   [controlClass]
  defaultSort: { field: controlClass, asc: true }
  key: [controlClass]
permissions: [ledger.read, fin.ar.read, fin.ap.read, fin.fa.read]
timeSlice: { knownAt: knownAt }
report:
  period: { to: asOf }
---*/
WITH ar_docs AS (
    SELECT i.{{FinInvoice.invoiceId}} AS doc_key, i.{{FinInvoice.kind}} AS doc_kind,
           i.{{FinInvoice.total}} AS doc_total, i.{{FinInvoice.totalUsd}} AS doc_total_usd
    FROM {{FinInvoice}} i
    WHERE i.{{FinInvoice.status}} IN ('POSTED', 'VOID', 'WRITTEN_OFF')
      AND COALESCE(i.{{FinInvoice.postingDate}}, i.{{FinInvoice.invoiceDate}}) <= :asOf
      AND (i.{{FinInvoice.voidDate}} IS NULL OR i.{{FinInvoice.voidDate}} > :asOf)
),
ar_cleared AS (
    SELECT a.{{FinApplication.invoiceId}} AS doc_key,
           SUM(a.{{FinApplication.amount}} + COALESCE(a.{{FinApplication.discount}}, 0)) AS done,
           SUM(a.{{FinApplication.amountUsd}} + COALESCE(a.{{FinApplication.discount}}, 0)) AS done_usd
    FROM {{FinApplication}} a
    WHERE a.{{FinApplication.applicationDate}} <= :asOf
    GROUP BY a.{{FinApplication.invoiceId}}
    UNION ALL
    SELECT CAST(a.{{FinApplication.sourceId}} AS uuid), SUM(a.{{FinApplication.amount}}),
           SUM(COALESCE(a.{{FinApplication.sourceAmountUsd}}, a.{{FinApplication.amountUsd}}))
    FROM {{FinApplication}} a
    WHERE a.{{FinApplication.sourceKind}} = 'CREDIT_MEMO' AND a.{{FinApplication.applicationDate}} <= :asOf
    GROUP BY a.{{FinApplication.sourceId}}
),
-- Each document's open amount in its currency and in dollars, as the receivables aging has them.
ar_open AS (
    SELECT CASE WHEN d.doc_kind = 'CREDIT_MEMO' THEN -1 ELSE 1 END
               * (d.doc_total - COALESCE((SELECT SUM(c.done) FROM ar_cleared c WHERE c.doc_key = d.doc_key), 0))
               AS open_amt,
           CASE WHEN d.doc_kind = 'CREDIT_MEMO' THEN -1 ELSE 1 END
               * (d.doc_total_usd - COALESCE((SELECT SUM(c.done_usd) FROM ar_cleared c WHERE c.doc_key = d.doc_key), 0))
               + COALESCE((SELECT SUM(l.{{FinFxRevaluationLine.difference}})
                   FROM {{FinFxRevaluationLine}} l
                   JOIN {{FinFxRevaluationRun}} r ON r.{{FinFxRevaluationRun.runId}} = l.{{FinFxRevaluationLine.runId}}
                   WHERE l.{{FinFxRevaluationLine.kind}} = 'RECEIVABLE'
                     AND l.{{FinFxRevaluationLine.documentId}} = CAST(d.doc_key AS varchar)
                     AND r.{{FinFxRevaluationRun.revaluationDate}} <= :asOf
                     AND r.{{FinFxRevaluationRun.reversalDate}} > :asOf), 0) AS open_usd
    FROM ar_docs d
),
-- The aging lists the documents still open in their currency only.
ar_total AS (
    SELECT COALESCE(SUM(open_usd), 0) AS open_total FROM ar_open WHERE open_amt <> 0
),
ap_docs AS (
    SELECT b.{{FinBill.billId}} AS doc_key, b.{{FinBill.kind}} AS doc_kind,
           COALESCE(b.{{FinBill.totalUsd}}, b.{{FinBill.total}}) AS doc_total_usd
    FROM {{FinBill}} b
    WHERE b.{{FinBill.status}} IN ('POSTED', 'VOID')
      AND COALESCE(b.{{FinBill.postingDate}}, b.{{FinBill.invoiceDate}}) <= :asOf
      AND (b.{{FinBill.voidDate}} IS NULL OR b.{{FinBill.voidDate}} > :asOf)
),
ap_cleared AS (
    SELECT a.{{FinApApplication.billId}} AS doc_key,
           SUM(COALESCE(a.{{FinApApplication.amountUsd}}, a.{{FinApApplication.amount}})
               + COALESCE(a.{{FinApApplication.discount}}, 0)) AS done_usd
    FROM {{FinApApplication}} a
    WHERE a.{{FinApApplication.applicationDate}} <= :asOf
    GROUP BY a.{{FinApApplication.billId}}
    UNION ALL
    SELECT CAST(a.{{FinApApplication.sourceId}} AS uuid),
           SUM(COALESCE(a.{{FinApApplication.sourceAmountUsd}}, a.{{FinApApplication.amountUsd}},
               a.{{FinApApplication.amount}}))
    FROM {{FinApApplication}} a
    WHERE a.{{FinApApplication.sourceKind}} = 'CREDIT' AND a.{{FinApApplication.applicationDate}} <= :asOf
    GROUP BY a.{{FinApApplication.sourceId}}
),
ap_total AS (
    SELECT COALESCE(SUM(
           CASE WHEN d.doc_kind = 'CREDIT' THEN -1 ELSE 1 END
               * (d.doc_total_usd - COALESCE((SELECT SUM(c.done_usd) FROM ap_cleared c WHERE c.doc_key = d.doc_key), 0))
               - COALESCE((SELECT SUM(l.{{FinFxRevaluationLine.difference}})
                   FROM {{FinFxRevaluationLine}} l
                   JOIN {{FinFxRevaluationRun}} r ON r.{{FinFxRevaluationRun.runId}} = l.{{FinFxRevaluationLine.runId}}
                   WHERE l.{{FinFxRevaluationLine.kind}} = 'PAYABLE'
                     AND l.{{FinFxRevaluationLine.documentId}} = CAST(d.doc_key AS varchar)
                     AND r.{{FinFxRevaluationRun.revaluationDate}} <= :asOf
                     AND r.{{FinFxRevaluationRun.reversalDate}} > :asOf), 0)), 0) AS open_total
    FROM ap_docs d
),
fa_taken AS (
    SELECT l.{{FinDepreciationLine.assetId}} AS asset_key, SUM(l.{{FinDepreciationLine.amount}}) AS run_total
    FROM {{FinDepreciationLine}} l
    JOIN {{FinDepreciationRun}} r ON r.{{FinDepreciationRun.runId}} = l.{{FinDepreciationLine.runId}}
    WHERE r.{{FinDepreciationRun.status}} = 'POSTED' AND r.{{FinDepreciationRun.postingDate}} <= :asOf
    GROUP BY l.{{FinDepreciationLine.assetId}}
),
fa_total AS (
    SELECT COALESCE(SUM(a.{{FinAsset.cost}}), 0) AS cost_amount,
           COALESCE(SUM(COALESCE(a.{{FinAsset.openingAccumulated}}, 0) + COALESCE(t.run_total, 0)), 0) AS acc_amount
    FROM {{FinAsset}} a
    LEFT JOIN fa_taken t ON t.asset_key = a.{{FinAsset.assetId}}
    LEFT JOIN {{FinAssetDisposal}} d ON d.{{FinAssetDisposal.assetId}} = a.{{FinAsset.assetId}}
    LEFT JOIN {{FinBill}} b ON b.{{FinBill.billId}} = a.{{FinAsset.sourceBillId}}
    WHERE (a.{{FinAsset.active}} = true OR b.{{FinBill.voidDate}} > :asOf)
      AND a.{{FinAsset.inServiceDate}} <= :asOf
      AND (d.{{FinAssetDisposal.disposalDate}} IS NULL OR d.{{FinAssetDisposal.disposalDate}} > :asOf)
),
-- The control accounts' balances, debit positive, without period 13 of the day's fiscal year (as the close checks).
controls AS (
    SELECT f.{{FinAccount.controlClass}} AS ctl_class, a.{{LedgerAccount.accountId}} AS acct_key,
           a.{{LedgerAccount.accountCode}} AS code
    FROM {{FinAccount}} f
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = f.{{FinAccount.ledgerAccountId}}
    WHERE f.{{FinAccount.controlClass}} IN ('AR', 'AP', 'FA_COST', 'FA_ACCUM')
),
balances AS (
    SELECT c.ctl_class,
           SUM(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                    ELSE -e.{{LedgerEntry.amount}} END) AS net
    FROM controls c
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.accountId}} = c.acct_key
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = t.{{LedgerTransaction.transactionId}}
    WHERE fp.{{FinPosting.postingDate}} <= :asOf
      AND (fp.{{FinPosting.periodNo}} <> 13 OR fp.{{FinPosting.fiscalYear}} <> (
           SELECT MAX(yp.{{FinPeriod.fiscalYear}}) FROM {{FinPeriod}} yp
           WHERE yp.{{FinPeriod.startDate}} <= :asOf AND yp.{{FinPeriod.endDate}} >= :asOf))
      AND (CAST(:knownAt AS timestamptz) IS NULL OR t.{{LedgerTransaction.createdTime}} <= :knownAt)
    GROUP BY c.ctl_class
),
named AS (
    SELECT ctl_class, STRING_AGG(code, ', ' ORDER BY code) AS codes FROM controls GROUP BY ctl_class
),
sides (ctl_class, label, subledger_amount, ledger_sign) AS (
    SELECT 'AR', 'Receivables aging', (SELECT open_total FROM ar_total), 1
    UNION ALL SELECT 'AP', 'Payables aging', (SELECT open_total FROM ap_total), -1
    UNION ALL SELECT 'FA_COST', 'Asset register cost', (SELECT cost_amount FROM fa_total), 1
    UNION ALL SELECT 'FA_ACCUM', 'Asset register accumulated depreciation', (SELECT acc_amount FROM fa_total), -1
)
SELECT s.ctl_class AS controlClass, s.label AS name, n.codes AS accounts,
       s.subledger_amount AS subledger,
       s.ledger_sign * COALESCE(b.net, 0) AS ledger,
       s.subledger_amount - s.ledger_sign * COALESCE(b.net, 0) AS difference
FROM sides s
LEFT JOIN balances b ON b.ctl_class = s.ctl_class
LEFT JOIN named n ON n.ctl_class = s.ctl_class
