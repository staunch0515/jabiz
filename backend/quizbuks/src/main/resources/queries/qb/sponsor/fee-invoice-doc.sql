/*---
id: qb.sponsor.fee-invoice-doc
description: "Q87b: an invoice of the sponsor's as the qualified invoice shows it (the sponsor's own invoices only)"
entities: [QbFeeInvoice, QbSponsor]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:sponsor:QbFeeInvoice
  QbSponsor: urn:jabiz:dataset:sponsor:QbSponsor
params:
  invoiceId: { like: QbFeeInvoice.invoiceId, required: true, description: The invoice }
results:
  invoiceNo:   { from: QbFeeInvoice.invoiceNo }
  company:     { from: QbSponsor.company }
  contactName: { from: QbSponsor.contactName }
  period:      { from: QbFeeInvoice.period }
  issuedAt:    { from: QbFeeInvoice.issuedAt }
  dueOn:       { from: QbFeeInvoice.dueOn }
  billingMode: { from: QbFeeInvoice.billingMode }
  amount:      { from: QbFeeInvoice.amount }
  tax:         { from: QbFeeInvoice.tax }
  paidAtCharge: { from: QbFeeInvoice.paidAtCharge }
  due:         { from: QbFeeInvoice.due }
  total:       { from: QbFeeInvoice.total }
permissions: [qb.topup]
---*/
SELECT i.{{QbFeeInvoice.invoiceNo}} AS invoiceNo, s.{{QbSponsor.company}} AS company,
       s.{{QbSponsor.contactName}} AS contactName, i.{{QbFeeInvoice.period}} AS period,
       i.{{QbFeeInvoice.issuedAt}} AS issuedAt, i.{{QbFeeInvoice.dueOn}} AS dueOn,
       i.{{QbFeeInvoice.billingMode}} AS billingMode, i.{{QbFeeInvoice.amount}} AS amount,
       i.{{QbFeeInvoice.tax}} AS tax, i.{{QbFeeInvoice.paidAtCharge}} AS paidAtCharge, i.{{QbFeeInvoice.due}} AS due,
       i.{{QbFeeInvoice.total}} AS total
FROM {{QbFeeInvoice}} i
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = i.{{QbFeeInvoice.sponsorId}}
WHERE i.{{QbFeeInvoice.invoiceId}} = :invoiceId
