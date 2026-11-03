/*---
id: qb.admin.fee-invoices
description: The platform fee invoices (Q87), newest first, with the sponsor - for finance
entities: [QbFeeInvoice, QbSponsor]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
results:
  invoiceId:     { from: QbFeeInvoice.invoiceId }
  invoiceNo:     { from: QbFeeInvoice.invoiceNo }
  sponsorId:     { from: QbFeeInvoice.sponsorId }
  company:       { from: QbSponsor.company }
  operatorId:    { from: QbFeeInvoice.operatorId }
  period:        { from: QbFeeInvoice.period }
  billingMode:   { from: QbFeeInvoice.billingMode }
  amount:        { from: QbFeeInvoice.amount }
  tax:           { from: QbFeeInvoice.tax }
  total:         { from: QbFeeInvoice.total }
  status:        { from: QbFeeInvoice.status }
  issuedAt:      { from: QbFeeInvoice.issuedAt }
  dueOn:         { from: QbFeeInvoice.dueOn }
  paidAt:        { from: QbFeeInvoice.paidAt }
  remindersSent: { from: QbFeeInvoice.remindersSent }
  voidedAt:      { from: QbFeeInvoice.voidedAt }
  voidReason:    { from: QbFeeInvoice.voidReason }
list:
  filters: [sponsorId, operatorId, status, period, issuedAt, dueOn, invoiceNo]
  sorts:   [issuedAt, dueOn, total, status]
  defaultSort: { field: issuedAt, asc: false }
  key: [invoiceId]
permissions: [qb.admin.finance.read]
---*/
SELECT
    i.{{QbFeeInvoice.invoiceId}}     AS invoiceId,
    i.{{QbFeeInvoice.invoiceNo}}     AS invoiceNo,
    i.{{QbFeeInvoice.sponsorId}}     AS sponsorId,
    s.{{QbSponsor.company}}          AS company,
    i.{{QbFeeInvoice.operatorId}}    AS operatorId,
    i.{{QbFeeInvoice.period}}        AS period,
    i.{{QbFeeInvoice.billingMode}}   AS billingMode,
    i.{{QbFeeInvoice.amount}}        AS amount,
    i.{{QbFeeInvoice.tax}}           AS tax,
    i.{{QbFeeInvoice.total}}         AS total,
    i.{{QbFeeInvoice.status}}        AS status,
    i.{{QbFeeInvoice.issuedAt}}      AS issuedAt,
    i.{{QbFeeInvoice.dueOn}}         AS dueOn,
    i.{{QbFeeInvoice.paidAt}}        AS paidAt,
    i.{{QbFeeInvoice.remindersSent}} AS remindersSent,
    i.{{QbFeeInvoice.voidedAt}}      AS voidedAt,
    i.{{QbFeeInvoice.voidReason}}    AS voidReason
FROM {{QbFeeInvoice}} i
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = i.{{QbFeeInvoice.sponsorId}}
