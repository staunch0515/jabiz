/*---
id: finance.ar.invoice_register
description: >-
  Invoices and credit memos dated in a range (FIN-UI-004): number, kind, customer, dates, currency, totals and what is
  open, in the document's currency and in US dollars with credit memos negative, so that the dollar columns add up.
  Drafts only when asked for by their state. Run with knownAt, the register as recorded at that time.
entities: [FinInvoice, FinCustomer]
params:
  from:         { like: FinInvoice.invoiceDate, required: true, description: first invoice date }
  to:           { like: FinInvoice.invoiceDate, required: true, description: last invoice date }
  customerCode: { like: FinInvoice.customerCode, description: "only this customer's, if given" }
  status:       { like: FinInvoice.status, description: "only documents in this state, if given; drafts only so" }
  kind:         { like: FinInvoice.kind, description: "only invoices or only credit memos, if given" }
results:
  invoiceId:     { from: FinInvoice.invoiceId }
  invoiceNo:     { from: FinInvoice.invoiceNo }
  kind:          { from: FinInvoice.kind }
  invoiceDate:   { from: FinInvoice.invoiceDate }
  postingDate:   { from: FinInvoice.postingDate }
  dueDate:       { from: FinInvoice.dueDate }
  customerCode:  { from: FinInvoice.customerCode }
  customerName:  { from: FinCustomer.legalName }
  reference:     { from: FinInvoice.reference }
  currency:      { from: FinInvoice.currency }
  total:         { from: FinInvoice.total }
  openAmount:    { from: FinInvoice.openAmount }
  totalUsd:      { from: FinInvoice.totalUsd }
  openAmountUsd: { from: FinInvoice.openAmountUsd }
  status:        { from: FinInvoice.status }
  glNo:          { from: FinInvoice.glNo }
list:
  filters: [invoiceNo, kind, customerCode, currency, status, glNo]
  sorts:   [invoiceNo, invoiceDate, dueDate, customerCode, totalUsd, openAmountUsd]
  defaultSort: { field: invoiceDate, asc: true }
  key: [invoiceId]
permissions: [fin.ar.read]
report:
  period: { from: from, to: to }
  landscape: true
---*/
SELECT
    i.{{FinInvoice.invoiceId}}    AS invoiceId,
    i.{{FinInvoice.invoiceNo}}    AS invoiceNo,
    i.{{FinInvoice.kind}}         AS kind,
    i.{{FinInvoice.invoiceDate}}  AS invoiceDate,
    COALESCE(i.{{FinInvoice.postingDate}}, i.{{FinInvoice.invoiceDate}}) AS postingDate,
    i.{{FinInvoice.dueDate}}      AS dueDate,
    i.{{FinInvoice.customerCode}} AS customerCode,
    c.{{FinCustomer.legalName}}   AS customerName,
    i.{{FinInvoice.reference}}    AS reference,
    i.{{FinInvoice.currency}}     AS currency,
    i.{{FinInvoice.total}}        AS total,
    i.{{FinInvoice.openAmount}}   AS openAmount,
    CASE WHEN i.{{FinInvoice.kind}} = 'CREDIT_MEMO' THEN -1 ELSE 1 END * i.{{FinInvoice.totalUsd}} AS totalUsd,
    CASE WHEN i.{{FinInvoice.kind}} = 'CREDIT_MEMO' THEN -1 ELSE 1 END * i.{{FinInvoice.openAmountUsd}} AS openAmountUsd,
    i.{{FinInvoice.status}}       AS status,
    i.{{FinInvoice.glNo}}         AS glNo
FROM {{FinInvoice}} i
LEFT JOIN {{FinCustomer}} c ON c.{{FinCustomer.customerCode}} = i.{{FinInvoice.customerCode}}
WHERE i.{{FinInvoice.invoiceDate}} BETWEEN :from AND :to
  AND (CAST(:customerCode AS text) IS NULL OR i.{{FinInvoice.customerCode}} = :customerCode)
  AND (CAST(:kind AS text) IS NULL OR i.{{FinInvoice.kind}} = :kind)
  AND (CASE WHEN CAST(:status AS text) IS NULL THEN i.{{FinInvoice.status}} <> 'DRAFT'
            ELSE i.{{FinInvoice.status}} = :status END)
