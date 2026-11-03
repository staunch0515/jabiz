/*---
id: qb.admin.fee-invoices
description: "Q87b: the sponsors' invoices of platform fees, newest first, with the sponsor's company and how much each still owes"
entities: [QbFeeInvoice, QbSponsor]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  status: { like: QbFeeInvoice.status, description: "Only invoices in this state (none: all)" }
results:
  invoiceId:   { from: QbFeeInvoice.invoiceId }
  invoiceNo:   { from: QbFeeInvoice.invoiceNo }
  sponsorId:   { from: QbFeeInvoice.sponsorId }
  company:     { from: QbSponsor.company }
  period:      { from: QbFeeInvoice.period }
  billingMode: { from: QbFeeInvoice.billingMode }
  total:       { from: QbFeeInvoice.total }
  due:         { from: QbFeeInvoice.due }
  status:      { from: QbFeeInvoice.status }
  issuedAt:    { from: QbFeeInvoice.issuedAt }
  dueOn:       { from: QbFeeInvoice.dueOn }
  reminders:   { from: QbFeeInvoice.reminders }
list:
  filters: [status, sponsorId, period]
  sorts: [issuedAt, dueOn, total]
  defaultSort: { field: issuedAt, asc: false }
  key: [invoiceId]
permissions: [qb.admin.finance.read]
---*/
SELECT i.{{QbFeeInvoice.invoiceId}} AS invoiceId, i.{{QbFeeInvoice.invoiceNo}} AS invoiceNo,
       i.{{QbFeeInvoice.sponsorId}} AS sponsorId, s.{{QbSponsor.company}} AS company,
       i.{{QbFeeInvoice.period}} AS period, i.{{QbFeeInvoice.billingMode}} AS billingMode,
       i.{{QbFeeInvoice.total}} AS total, i.{{QbFeeInvoice.due}} AS due, i.{{QbFeeInvoice.status}} AS status,
       i.{{QbFeeInvoice.issuedAt}} AS issuedAt, i.{{QbFeeInvoice.dueOn}} AS dueOn,
       i.{{QbFeeInvoice.reminders}} AS reminders
FROM {{QbFeeInvoice}} i
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = i.{{QbFeeInvoice.sponsorId}}
WHERE (CAST(:status AS text) IS NULL OR i.{{QbFeeInvoice.status}} = :status)
