/*---
id: qb.sponsor.fee-invoice-lines
description: The charges of one platform fee invoice of the sponsor's own (Q87), the lines of its document
entities: [QbFeeInvoice, QbFeeCharge]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:sponsor:QbFeeInvoice
  QbFeeCharge: urn:jabiz:dataset:sponsor:QbFeeCharge
params:
  invoiceId: { like: QbFeeInvoice.invoiceId, required: true, description: The invoice }
results:
  chargeId:    { from: QbFeeCharge.chargeId }
  kind:        { from: QbFeeCharge.kind }
  period:      { from: QbFeeCharge.period }
  description: { kind: { type: text, maxLength: 500 } }
  amount:      { from: QbFeeCharge.amount }
  tax:         { from: QbFeeCharge.tax }
  total:       { from: QbFeeCharge.total }
list:
  sorts: [chargeId]
  defaultSort: { field: chargeId, asc: true }
  key: [chargeId]
permissions: [qb.topup]
---*/
-- A prepaid invoice's one charge, or a postpaid month's postpaid charges. The description is the reason given (a
-- manual fee), else the kind's name as the document's language gives it is the kind column.
SELECT
    c.{{QbFeeCharge.chargeId}} AS chargeId,
    c.{{QbFeeCharge.kind}}     AS kind,
    c.{{QbFeeCharge.period}}   AS period,
    CAST(coalesce(c.{{QbFeeCharge.reason}}, '') AS varchar(500)) AS description,
    c.{{QbFeeCharge.amount}}   AS amount,
    c.{{QbFeeCharge.tax}}      AS tax,
    c.{{QbFeeCharge.total}}    AS total
FROM {{QbFeeInvoice}} i
JOIN {{QbFeeCharge}} c ON (i.{{QbFeeInvoice.chargeId}} IS NOT NULL
                               AND CAST(c.{{QbFeeCharge.chargeId}} AS text) = i.{{QbFeeInvoice.chargeId}})
                       OR (i.{{QbFeeInvoice.chargeId}} IS NULL
                               AND c.{{QbFeeCharge.sponsorId}} = i.{{QbFeeInvoice.sponsorId}}
                               AND c.{{QbFeeCharge.period}} = i.{{QbFeeInvoice.period}}
                               AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID')
WHERE i.{{QbFeeInvoice.invoiceId}} = :invoiceId
