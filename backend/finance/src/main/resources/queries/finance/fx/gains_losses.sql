/*---
id: finance.fx.gains_losses
description: >-
  Exchange gains and losses by document (FIN-FX-007): realized on settling receivables and payables in a foreign
  currency (receipts, payments, credits applied, and their reversals), and unrealized on a period end's revaluation,
  with its reversal the next day, of the days in the range. Gains are positive, so the total is the credit movement of
  the realized and unrealized accounts in the range.
entities: [FinApplication, FinInvoice, FinApApplication, FinBill, FinFxRevaluationRun, FinFxRevaluationLine]
params:
  from: { like: FinApplication.applicationDate, required: true, description: "the first day" }
  to:   { like: FinApplication.applicationDate, required: true, description: "the last day" }
results:
  kind:       { kind: { type: text, maxLength: 12 } }
  ledger:     { kind: { type: text, maxLength: 4 } }
  day:        { from: FinApplication.applicationDate }
  documentNo: { kind: { type: text, maxLength: 40 } }
  sourceNo:   { kind: { type: text, maxLength: 40 } }
  partyCode:  { kind: { type: text, maxLength: 20 } }
  currency:   { from: FinInvoice.currency }
  amount:     { from: FinApplication.fxGainLoss }
list:
  filters: [kind, ledger, documentNo, partyCode, currency]
  sorts:   [day, kind, documentNo, amount]
  defaultSort: { field: day, asc: true }
  key: [kind, day, documentNo, sourceNo]
permissions: [fin.journal.read]
report:
  period: { from: from, to: to }
  landscape: true
---*/
SELECT 'REALIZED' AS kind, 'AR' AS ledger, a.{{FinApplication.applicationDate}} AS day,
       i.{{FinInvoice.invoiceNo}} AS documentNo, a.{{FinApplication.sourceNo}} AS sourceNo,
       a.{{FinApplication.customerCode}} AS partyCode, i.{{FinInvoice.currency}} AS currency,
       a.{{FinApplication.fxGainLoss}} AS amount
FROM {{FinApplication}} a
JOIN {{FinInvoice}} i ON i.{{FinInvoice.invoiceId}} = a.{{FinApplication.invoiceId}}
WHERE a.{{FinApplication.fxGainLoss}} IS NOT NULL AND a.{{FinApplication.fxGainLoss}} <> 0
  AND a.{{FinApplication.applicationDate}} BETWEEN :from AND :to
UNION ALL
SELECT 'REALIZED', 'AP', a.{{FinApApplication.applicationDate}}, b.{{FinBill.billNo}}, a.{{FinApApplication.sourceNo}},
       a.{{FinApApplication.vendorCode}}, b.{{FinBill.currency}}, a.{{FinApApplication.fxGainLoss}}
FROM {{FinApApplication}} a
JOIN {{FinBill}} b ON b.{{FinBill.billId}} = a.{{FinApApplication.billId}}
WHERE a.{{FinApApplication.fxGainLoss}} IS NOT NULL AND a.{{FinApApplication.fxGainLoss}} <> 0
  AND a.{{FinApApplication.applicationDate}} BETWEEN :from AND :to
UNION ALL
SELECT 'UNREALIZED', CASE l.{{FinFxRevaluationLine.kind}} WHEN 'RECEIVABLE' THEN 'AR' WHEN 'PAYABLE' THEN 'AP'
       ELSE 'BANK' END, r.{{FinFxRevaluationRun.revaluationDate}}, l.{{FinFxRevaluationLine.documentNo}},
       r.{{FinFxRevaluationRun.runNo}}, l.{{FinFxRevaluationLine.partyCode}}, l.{{FinFxRevaluationLine.currency}},
       l.{{FinFxRevaluationLine.difference}}
FROM {{FinFxRevaluationLine}} l
JOIN {{FinFxRevaluationRun}} r ON r.{{FinFxRevaluationRun.runId}} = l.{{FinFxRevaluationLine.runId}}
WHERE l.{{FinFxRevaluationLine.difference}} <> 0
  AND r.{{FinFxRevaluationRun.revaluationDate}} BETWEEN :from AND :to
UNION ALL
SELECT 'REVERSAL', CASE l.{{FinFxRevaluationLine.kind}} WHEN 'RECEIVABLE' THEN 'AR' WHEN 'PAYABLE' THEN 'AP'
       ELSE 'BANK' END, r.{{FinFxRevaluationRun.reversalDate}}, l.{{FinFxRevaluationLine.documentNo}},
       r.{{FinFxRevaluationRun.runNo}} || '-R', l.{{FinFxRevaluationLine.partyCode}},
       l.{{FinFxRevaluationLine.currency}}, -l.{{FinFxRevaluationLine.difference}}
FROM {{FinFxRevaluationLine}} l
JOIN {{FinFxRevaluationRun}} r ON r.{{FinFxRevaluationRun.runId}} = l.{{FinFxRevaluationLine.runId}}
WHERE l.{{FinFxRevaluationLine.difference}} <> 0
  AND r.{{FinFxRevaluationRun.reversalDate}} BETWEEN :from AND :to
