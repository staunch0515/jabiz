/*---
id: finance.ar.aging
description: >-
  The receivables aging on a day (FIN-AR-010): every invoice and credit memo of the receivables subledger with what
  was open of it at the end of that day — its total less what was applied to it, discounted, written off or credited
  by then — in its currency and in US dollars, by customer, with its bucket by due date (current, then up to the
  three limits, then over the last) or by invoice date. The total in US dollars equals the receivables account at that
  day. Applications dated later do not count, so a later receipt or a correction leaves an earlier day's aging as it
  was; run with knownAt, the books as recorded at that time. For one customer it is the open-item statement (FIN-AR-009).
entities: [FinInvoice, FinApplication, FinCustomer]
params:
  agingDate:    { like: FinApplication.applicationDate, required: true, description: "the day the aging is at, at its end" }
  basis:        { kind: { type: text, maxLength: 7 }, description: "DUE (the default): by due date; INVOICE: by invoice date" }
  limit1:       { kind: { type: numeric, precision: 4, scale: 0 }, description: "days to the end of the first bucket; 30 if not given" }
  limit2:       { kind: { type: numeric, precision: 4, scale: 0 }, description: "the second; 60 if not given" }
  limit3:       { kind: { type: numeric, precision: 4, scale: 0 }, description: "the third; 90 if not given" }
  customerCode: { like: FinInvoice.customerCode, description: "one customer only: the open-item statement" }
results:
  customerCode:  { from: FinInvoice.customerCode }
  legalName:     { from: FinCustomer.legalName }
  documentNo:    { from: FinInvoice.invoiceNo }
  kind:          { from: FinInvoice.kind }
  invoiceDate:   { from: FinInvoice.invoiceDate }
  dueDate:       { from: FinInvoice.dueDate }
  daysPastDue:   { kind: { type: numeric, precision: 6, scale: 0 } }
  bucketNo:      { kind: { type: numeric, precision: 1, scale: 0 } }
  bucket:        { kind: { type: text, maxLength: 12 } }
  currency:      { from: FinInvoice.currency }
  openAmount:    { from: FinInvoice.openAmount }
  openAmountUsd: { from: FinInvoice.openAmountUsd }
list:
  filters: [customerCode, kind, bucketNo, bucket, currency]
  sorts:   [customerCode, documentNo, dueDate, daysPastDue, openAmountUsd]
  defaultSort: { field: customerCode, asc: true }
  key: [documentNo]
permissions: [fin.ar.read]
report:
  period: { to: agingDate }
---*/
WITH docs AS (
    SELECT i.{{FinInvoice.invoiceId}} AS doc_key, i.{{FinInvoice.invoiceNo}} AS doc_no,
           i.{{FinInvoice.kind}} AS doc_kind, i.{{FinInvoice.customerCode}} AS cust,
           i.{{FinInvoice.invoiceDate}} AS doc_date, i.{{FinInvoice.dueDate}} AS due_on,
           i.{{FinInvoice.currency}} AS cur, i.{{FinInvoice.total}} AS doc_total,
           i.{{FinInvoice.totalUsd}} AS doc_total_usd
    FROM {{FinInvoice}} i
    WHERE i.{{FinInvoice.status}} IN ('POSTED', 'VOID', 'WRITTEN_OFF')
      AND i.{{FinInvoice.invoiceDate}} <= :agingDate
      -- A document voided later was still open on the day.
      AND (i.{{FinInvoice.voidDate}} IS NULL OR i.{{FinInvoice.voidDate}} > :agingDate)
      AND (CAST(:customerCode AS varchar) IS NULL OR i.{{FinInvoice.customerCode}} = :customerCode)
),
cleared AS (
    -- What was taken off each invoice by the day: cash, discounts, credits, write-offs (recoveries negative)…
    SELECT a.{{FinApplication.invoiceId}} AS doc_key,
           SUM(a.{{FinApplication.amount}} + COALESCE(a.{{FinApplication.discount}}, 0)) AS done,
           SUM(a.{{FinApplication.amountUsd}} + COALESCE(a.{{FinApplication.discount}}, 0)) AS done_usd
    FROM {{FinApplication}} a
    WHERE a.{{FinApplication.applicationDate}} <= :agingDate
    GROUP BY a.{{FinApplication.invoiceId}}
    UNION ALL
    -- …and what of each credit memo was applied to invoices.
    SELECT CAST(a.{{FinApplication.sourceId}} AS uuid), SUM(a.{{FinApplication.amount}}),
           SUM(a.{{FinApplication.amountUsd}})
    FROM {{FinApplication}} a
    WHERE a.{{FinApplication.sourceKind}} = 'CREDIT_MEMO' AND a.{{FinApplication.applicationDate}} <= :agingDate
    GROUP BY a.{{FinApplication.sourceId}}
),
open_docs AS (
    SELECT d.*,
           CASE WHEN d.doc_kind = 'CREDIT_MEMO' THEN -1 ELSE 1 END
               * (d.doc_total - COALESCE((SELECT SUM(c.done) FROM cleared c WHERE c.doc_key = d.doc_key), 0))
               AS open_amt,
           CASE WHEN d.doc_kind = 'CREDIT_MEMO' THEN -1 ELSE 1 END
               * (d.doc_total_usd - COALESCE((SELECT SUM(c.done_usd) FROM cleared c WHERE c.doc_key = d.doc_key), 0))
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
SELECT o.cust        AS customerCode,
       c.{{FinCustomer.legalName}} AS legalName,
       o.doc_no      AS documentNo,
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
LEFT JOIN {{FinCustomer}} c ON c.{{FinCustomer.customerCode}} = o.cust
WHERE o.open_amt <> 0
