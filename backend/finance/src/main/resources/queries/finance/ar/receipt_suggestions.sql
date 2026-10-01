/*---
id: finance.ar.receipt_suggestions
description: >-
  Which open invoices a customer's receipt is likely to pay (FIN-AR-008): those whose number the receipt's reference
  names first, then those whose open amount it matches, or matches less the early-payment discount still on offer
  on its day (FIN-AR-002), then the oldest. Each with the discount offered on that day and its last day.
entities: [FinInvoice, FinApplication, FinPaymentTerms]
params:
  customerCode: { like: FinInvoice.customerCode, required: true }
  onDate:       { like: FinApplication.applicationDate, required: true, description: the day the money came in }
  amount:       { like: FinInvoice.openAmount, description: the receipt's amount }
  reference:    { like: FinInvoice.reference, description: "the receipt's reference, such as the remittance advice's" }
results:
  rank:              { kind: { type: numeric, precision: 1, scale: 0 } }
  matched:           { kind: { type: text, maxLength: 20 } }
  invoiceId:         { from: FinInvoice.invoiceId }
  invoiceNo:         { from: FinInvoice.invoiceNo }
  invoiceDate:       { from: FinInvoice.invoiceDate }
  dueDate:           { from: FinInvoice.dueDate }
  openAmount:        { from: FinInvoice.openAmount }
  discountOffered:   { from: FinInvoice.openAmount }
  discountUntil:     { from: FinInvoice.dueDate }
list:
  filters: [matched]
  sorts:   [rank, invoiceDate, invoiceNo]
  defaultSort: { field: rank, asc: true }
  key: [invoiceNo]
permissions: [fin.ar.read]
---*/
WITH open_items AS (
    SELECT i.{{FinInvoice.invoiceId}} AS inv_key, i.{{FinInvoice.invoiceNo}} AS inv_no,
           i.{{FinInvoice.invoiceDate}} AS inv_date, i.{{FinInvoice.dueDate}} AS due_on,
           i.{{FinInvoice.openAmount}} AS open_amt, i.{{FinInvoice.total}} AS inv_total,
           CASE WHEN p.{{FinPaymentTerms.discountDays}} IS NULL THEN NULL
                WHEN p.{{FinPaymentTerms.endOfMonth}}
                    THEN CAST(date_trunc('month', i.{{FinInvoice.invoiceDate}}) + INTERVAL '1 month' - INTERVAL '1 day' AS date)
                         + CAST(p.{{FinPaymentTerms.discountDays}} AS integer)
                ELSE i.{{FinInvoice.invoiceDate}} + CAST(p.{{FinPaymentTerms.discountDays}} AS integer) END AS disc_until,
           p.{{FinPaymentTerms.discountPercent}} AS disc_pct
    FROM {{FinInvoice}} i
    LEFT JOIN {{FinPaymentTerms}} p ON p.{{FinPaymentTerms.termsCode}} = i.{{FinInvoice.termsCode}}
    WHERE i.{{FinInvoice.customerCode}} = :customerCode AND i.{{FinInvoice.kind}} = 'INVOICE'
      AND i.{{FinInvoice.status}} = 'POSTED' AND i.{{FinInvoice.openAmount}} > 0
),
offered AS (
    SELECT o.*,
           CASE WHEN o.disc_until IS NOT NULL AND CAST(:onDate AS date) <= o.disc_until
                THEN GREATEST(ROUND(o.inv_total * o.disc_pct / 100, 2) - COALESCE((
                    SELECT SUM(a.{{FinApplication.discount}}) FROM {{FinApplication}} a
                    WHERE a.{{FinApplication.invoiceId}} = o.inv_key), 0), 0)
                ELSE 0 END AS disc
    FROM open_items o
)
SELECT CASE WHEN CAST(:reference AS varchar) IS NOT NULL AND POSITION(o.inv_no IN :reference) > 0 THEN 1
            WHEN CAST(:amount AS numeric) = o.open_amt THEN 2
            WHEN o.disc > 0 AND CAST(:amount AS numeric) = o.open_amt - o.disc THEN 3
            ELSE 4 END AS rank,
       CASE WHEN CAST(:reference AS varchar) IS NOT NULL AND POSITION(o.inv_no IN :reference) > 0 THEN 'REFERENCE'
            WHEN CAST(:amount AS numeric) = o.open_amt THEN 'AMOUNT'
            WHEN o.disc > 0 AND CAST(:amount AS numeric) = o.open_amt - o.disc THEN 'AMOUNT_LESS_DISCOUNT'
            ELSE 'OLDEST' END AS matched,
       o.inv_key AS invoiceId, o.inv_no AS invoiceNo, o.inv_date AS invoiceDate, o.due_on AS dueDate,
       o.open_amt AS openAmount, o.disc AS discountOffered,
       CASE WHEN o.disc > 0 THEN o.disc_until END AS discountUntil
FROM offered o
