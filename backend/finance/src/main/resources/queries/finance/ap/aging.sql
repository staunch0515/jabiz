/*---
id: finance.ap.aging
description: >-
  The payables aging on a day (FIN-AP-009): every bill and vendor credit of the payables subledger with what was open
  of it at the end of that day — its total less what was applied to it or paid by then — by vendor, with its bucket by
  due date (current, then up to the three limits, then over the last) or by invoice date. Vendor credits are negative.
  Documents in a foreign currency show what is open in it and in US dollars (F7, FIN-FX-007); the total in US dollars
  equals the payables account at that day. Applications dated later do not count, so a later payment leaves
  an earlier day's aging as it was; run with knownAt, the books as recorded at that time.
  Between a revaluation's day and its reversal the next day, a foreign document's dollars are as remeasured.
entities: [FinFxRevaluationRun, FinFxRevaluationLine, FinBill, FinApApplication, FinVendor]
params:
  agingDate:  { like: FinApApplication.applicationDate, required: true, description: "the day the aging is at, at its end" }
  basis:      { kind: { type: text, maxLength: 7 }, description: "DUE (the default): by due date; INVOICE: by invoice date" }
  limit1:     { kind: { type: numeric, precision: 4, scale: 0 }, description: "days to the end of the first bucket; 30 if not given" }
  limit2:     { kind: { type: numeric, precision: 4, scale: 0 }, description: "the second; 60 if not given" }
  limit3:     { kind: { type: numeric, precision: 4, scale: 0 }, description: "the third; 90 if not given" }
  vendorCode: { like: FinBill.vendorCode, description: "one vendor only" }
results:
  vendorCode:      { from: FinBill.vendorCode }
  legalName:       { from: FinVendor.legalName }
  documentNo:      { from: FinBill.billNo }
  vendorInvoiceNo: { from: FinBill.vendorInvoiceNo }
  kind:            { from: FinBill.kind }
  invoiceDate:     { from: FinBill.invoiceDate }
  dueDate:         { from: FinBill.dueDate }
  daysPastDue:     { kind: { type: numeric, precision: 6, scale: 0 } }
  bucketNo:        { kind: { type: numeric, precision: 1, scale: 0 } }
  bucket:          { kind: { type: text, maxLength: 12 } }
  currency:        { from: FinBill.currency }
  openAmount:      { from: FinBill.openAmount }
  openAmountUsd:   { from: FinBill.openAmountUsd }
list:
  filters: [vendorCode, kind, bucketNo, bucket, currency]
  sorts:   [vendorCode, documentNo, dueDate, daysPastDue, openAmount]
  defaultSort: { field: vendorCode, asc: true }
  key: [documentNo]
permissions: [fin.ap.read]
report:
  period: { to: agingDate }
---*/
WITH docs AS (
    SELECT b.{{FinBill.billId}} AS doc_key, b.{{FinBill.billNo}} AS doc_no, b.{{FinBill.kind}} AS doc_kind,
           b.{{FinBill.vendorCode}} AS vend, b.{{FinBill.vendorInvoiceNo}} AS vend_no,
           b.{{FinBill.invoiceDate}} AS doc_date, b.{{FinBill.dueDate}} AS due_on, b.{{FinBill.total}} AS doc_total,
           b.{{FinBill.currency}} AS cur,
           -- Bills from before F7 are in US dollars and keep no dollar column.
           COALESCE(b.{{FinBill.totalUsd}}, b.{{FinBill.total}}) AS doc_total_usd
    FROM {{FinBill}} b
    WHERE b.{{FinBill.status}} IN ('POSTED', 'VOID')
      AND b.{{FinBill.invoiceDate}} <= :agingDate
      -- A document voided later was still open on the day.
      AND (b.{{FinBill.voidDate}} IS NULL OR b.{{FinBill.voidDate}} > :agingDate)
      AND (CAST(:vendorCode AS varchar) IS NULL OR b.{{FinBill.vendorCode}} = :vendorCode)
),
cleared AS (
    -- What was taken off each bill by the day: payments, discounts, credits…
    SELECT a.{{FinApApplication.billId}} AS doc_key,
           SUM(a.{{FinApApplication.amount}} + COALESCE(a.{{FinApApplication.discount}}, 0)) AS done,
           SUM(COALESCE(a.{{FinApApplication.amountUsd}}, a.{{FinApApplication.amount}})
               + COALESCE(a.{{FinApApplication.discount}}, 0)) AS done_usd
    FROM {{FinApApplication}} a
    WHERE a.{{FinApApplication.applicationDate}} <= :agingDate
    GROUP BY a.{{FinApApplication.billId}}
    UNION ALL
    -- …and what of each vendor credit was applied to bills, at the credit's own rate.
    SELECT CAST(a.{{FinApApplication.sourceId}} AS uuid), SUM(a.{{FinApApplication.amount}}),
           SUM(COALESCE(a.{{FinApApplication.sourceAmountUsd}}, a.{{FinApApplication.amountUsd}},
               a.{{FinApApplication.amount}}))
    FROM {{FinApApplication}} a
    WHERE a.{{FinApApplication.sourceKind}} = 'CREDIT' AND a.{{FinApApplication.applicationDate}} <= :agingDate
    GROUP BY a.{{FinApApplication.sourceId}}
),
open_docs AS (
    SELECT d.*,
           CASE WHEN d.doc_kind = 'CREDIT' THEN -1 ELSE 1 END
               * (d.doc_total - COALESCE((SELECT SUM(c.done) FROM cleared c WHERE c.doc_key = d.doc_key), 0))
               AS open_amt,
           CASE WHEN d.doc_kind = 'CREDIT' THEN -1 ELSE 1 END
               * (d.doc_total_usd - COALESCE((SELECT SUM(c.done_usd) FROM cleared c WHERE c.doc_key = d.doc_key), 0))
               -- Between a revaluation's day and its reversal the dollars are as remeasured (F7, FIN-FX-007); a
               -- revaluation line carries what is owed as negative.
               - COALESCE((SELECT SUM(l.{{FinFxRevaluationLine.difference}})
                   FROM {{FinFxRevaluationLine}} l
                   JOIN {{FinFxRevaluationRun}} r ON r.{{FinFxRevaluationRun.runId}} = l.{{FinFxRevaluationLine.runId}}
                   WHERE l.{{FinFxRevaluationLine.kind}} = 'PAYABLE'
                     AND l.{{FinFxRevaluationLine.documentId}} = CAST(d.doc_key AS varchar)
                     AND r.{{FinFxRevaluationRun.revaluationDate}} <= :agingDate
                     AND r.{{FinFxRevaluationRun.reversalDate}} > :agingDate), 0)
               AS open_usd,
           CAST(:agingDate AS date) - CASE WHEN COALESCE(CAST(:basis AS varchar), 'DUE') = 'INVOICE'
               THEN d.doc_date ELSE COALESCE(d.due_on, d.doc_date) END AS age
    FROM docs d
),
limits AS (
    SELECT CAST(COALESCE(:limit1, 30) AS integer) AS l1, CAST(COALESCE(:limit2, 60) AS integer) AS l2,
           CAST(COALESCE(:limit3, 90) AS integer) AS l3,
           COALESCE(CAST(:basis AS varchar), 'DUE') = 'INVOICE' AS by_invoice
)
SELECT o.vend        AS vendorCode,
       v.{{FinVendor.legalName}} AS legalName,
       o.doc_no      AS documentNo,
       o.vend_no     AS vendorInvoiceNo,
       o.doc_kind    AS kind,
       o.doc_date    AS invoiceDate,
       o.due_on      AS dueDate,
       o.age         AS daysPastDue,
       CASE WHEN o.age <= CASE WHEN l.by_invoice THEN l.l1 ELSE 0 END THEN 0
            WHEN o.age <= l.l1 THEN 1
            WHEN o.age <= l.l2 THEN 2
            WHEN o.age <= l.l3 THEN 3
            ELSE 4 END AS bucketNo,
       CASE WHEN l.by_invoice AND o.age <= l.l1 THEN '0-' || l.l1
            WHEN o.age <= 0 THEN 'Current'
            WHEN o.age <= l.l1 THEN '1-' || l.l1
            WHEN o.age <= l.l2 THEN (l.l1 + 1) || '-' || l.l2
            WHEN o.age <= l.l3 THEN (l.l2 + 1) || '-' || l.l3
            ELSE 'Over ' || l.l3 END AS bucket,
       o.cur         AS currency,
       o.open_amt    AS openAmount,
       o.open_usd    AS openAmountUsd
FROM open_docs o
CROSS JOIN limits l
LEFT JOIN {{FinVendor}} v ON v.{{FinVendor.vendorCode}} = o.vend
WHERE o.open_amt <> 0 OR o.open_usd <> 0
