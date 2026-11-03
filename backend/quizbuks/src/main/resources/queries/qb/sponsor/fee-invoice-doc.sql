/*---
id: qb.sponsor.fee-invoice-doc
description: One platform fee invoice of the sponsor's own, as its document shows it (Q87, the document qb.fee_invoice)
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
  billingMode: { from: QbFeeInvoice.billingMode }
  issuedAt:    { from: QbFeeInvoice.issuedAt }
  dueOn:       { from: QbFeeInvoice.dueOn }
  status:      { from: QbFeeInvoice.status }
  amount:      { from: QbFeeInvoice.amount }
  tax:         { from: QbFeeInvoice.tax }
  total:       { from: QbFeeInvoice.total }
permissions: [qb.topup]
---*/
-- Through the sponsor's own datasets: another sponsor's invoice yields no row (CLAUDE.md: a layout a sponsor can read
-- is limited to their own subjects).
SELECT
    i.{{QbFeeInvoice.invoiceNo}}   AS invoiceNo,
    s.{{QbSponsor.company}}        AS company,
    s.{{QbSponsor.contactName}}    AS contactName,
    i.{{QbFeeInvoice.period}}      AS period,
    i.{{QbFeeInvoice.billingMode}} AS billingMode,
    i.{{QbFeeInvoice.issuedAt}}    AS issuedAt,
    i.{{QbFeeInvoice.dueOn}}       AS dueOn,
    i.{{QbFeeInvoice.status}}      AS status,
    i.{{QbFeeInvoice.amount}}      AS amount,
    i.{{QbFeeInvoice.tax}}         AS tax,
    i.{{QbFeeInvoice.total}}       AS total
FROM {{QbFeeInvoice}} i
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = i.{{QbFeeInvoice.sponsorId}}
WHERE i.{{QbFeeInvoice.invoiceId}} = :invoiceId
