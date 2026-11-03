/*---
id: qb.sponsor.fee-invoice-lines
description: "Q87b: the charges an invoice of the sponsor's is made of (the qualified invoice's lines), by tax rate"
entities: [QbFeeInvoice, QbFeeCharge]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:sponsor:QbFeeInvoice
  QbFeeCharge: urn:jabiz:dataset:sponsor:QbFeeCharge
params:
  invoiceId: { like: QbFeeInvoice.invoiceId, required: true, description: The invoice }
results:
  chargedAt: { from: QbFeeCharge.chargedAt }
  kind:      { from: QbFeeCharge.kind }
  description: { from: QbFeeCharge.note }
  taxRate:   { from: QbFeeCharge.taxRate }
  amount:    { from: QbFeeCharge.amount }
list:
  sorts: [chargedAt]
  defaultSort: { field: chargedAt, asc: true }
  key: [chargedAt, kind]
permissions: [qb.topup]
---*/
SELECT c.{{QbFeeCharge.chargedAt}} AS chargedAt, c.{{QbFeeCharge.kind}} AS kind, c.{{QbFeeCharge.note}} AS description,
       c.{{QbFeeCharge.taxRate}} AS taxRate, c.{{QbFeeCharge.amount}} AS amount
FROM {{QbFeeInvoice}} i
JOIN {{QbFeeCharge}} c ON c.{{QbFeeCharge.sponsorId}} = i.{{QbFeeInvoice.sponsorId}}
    AND c.{{QbFeeCharge.period}} = i.{{QbFeeInvoice.period}}
WHERE i.{{QbFeeInvoice.invoiceId}} = :invoiceId
