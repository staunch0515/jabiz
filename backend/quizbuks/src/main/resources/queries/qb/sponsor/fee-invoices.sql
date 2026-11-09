/*---
id: qb.sponsor.fee-invoices
description: The sponsor's platform fee invoices (Q87), newest first - what is owed, due when, paid when
entities: [QbFeeInvoice]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:sponsor:QbFeeInvoice
results:
  invoiceId:     { from: QbFeeInvoice.invoiceId }
  invoiceNo:     { from: QbFeeInvoice.invoiceNo }
  period:        { from: QbFeeInvoice.period }
  billingMode:   { from: QbFeeInvoice.billingMode }
  amount:        { from: QbFeeInvoice.amount }
  tax:           { from: QbFeeInvoice.tax }
  total:         { from: QbFeeInvoice.total }
  paidAmount:    { from: QbFeeInvoice.paidAmount }
  status:        { from: QbFeeInvoice.status }
  issuedAt:      { from: QbFeeInvoice.issuedAt }
  dueOn:         { from: QbFeeInvoice.dueOn }
  paidAt:        { from: QbFeeInvoice.paidAt }
  documentRunId: { from: QbFeeInvoice.documentRunId }
list:
  filters: [status, period, issuedAt]
  sorts:   [issuedAt, dueOn, total]
  defaultSort: { field: issuedAt, asc: false }
  key: [invoiceId]
permissions: [qb.sponsor.finance.read]
---*/
SELECT
    i.{{QbFeeInvoice.invoiceId}}     AS invoiceId,
    i.{{QbFeeInvoice.invoiceNo}}     AS invoiceNo,
    i.{{QbFeeInvoice.period}}        AS period,
    i.{{QbFeeInvoice.billingMode}}   AS billingMode,
    i.{{QbFeeInvoice.amount}}        AS amount,
    i.{{QbFeeInvoice.tax}}           AS tax,
    i.{{QbFeeInvoice.total}}         AS total,
    i.{{QbFeeInvoice.paidAmount}}    AS paidAmount,
    i.{{QbFeeInvoice.status}}        AS status,
    i.{{QbFeeInvoice.issuedAt}}      AS issuedAt,
    i.{{QbFeeInvoice.dueOn}}         AS dueOn,
    i.{{QbFeeInvoice.paidAt}}        AS paidAt,
    i.{{QbFeeInvoice.documentRunId}} AS documentRunId
FROM {{QbFeeInvoice}} i
