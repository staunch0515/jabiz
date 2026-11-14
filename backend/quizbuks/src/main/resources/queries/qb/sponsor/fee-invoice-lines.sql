/*---
id: qb.sponsor.fee-invoice-lines
description: The charges of one platform fee invoice of the sponsor's own (Q87), the lines of its document
entities: [QbFeeInvoice, QbFeeCharge, QbPublication]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:sponsor:QbFeeInvoice
  QbFeeCharge: urn:jabiz:dataset:sponsor:QbFeeCharge
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
params:
  invoiceId: { like: QbFeeInvoice.invoiceId, required: true, description: The invoice }
results:
  chargeId:    { from: QbFeeCharge.chargeId }
  kind:        { from: QbFeeCharge.kind }
  period:      { from: QbFeeCharge.period }
  description: { kind: { type: text, maxLength: 500 } }
  taxRate:     { from: QbFeeCharge.taxRate }
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
-- manual fee), else the name of the publication the fee is for (F-M13: the source key of a distribution, an audience
-- or an exposure fee is "<kind>:<publication>[:<month>]"), else empty (the monthly fee: its month is the period).
SELECT
    c.{{QbFeeCharge.chargeId}} AS chargeId,
    c.{{QbFeeCharge.kind}}     AS kind,
    c.{{QbFeeCharge.period}}   AS period,
    CAST(coalesce(c.{{QbFeeCharge.reason}}, pb.{{QbPublication.name}}, '') AS varchar(500)) AS description,
    c.{{QbFeeCharge.taxRate}}  AS taxRate,
    c.{{QbFeeCharge.amount}}   AS amount,
    c.{{QbFeeCharge.tax}}      AS tax,
    c.{{QbFeeCharge.total}}    AS total
FROM {{QbFeeInvoice}} i
JOIN {{QbFeeCharge}} c ON (i.{{QbFeeInvoice.chargeId}} IS NOT NULL
                               AND CAST(c.{{QbFeeCharge.chargeId}} AS text) = i.{{QbFeeInvoice.chargeId}})
                       OR (i.{{QbFeeInvoice.chargeId}} IS NULL
                               AND c.{{QbFeeCharge.sponsorId}} = i.{{QbFeeInvoice.sponsorId}}
                               AND c.{{QbFeeCharge.period}} = i.{{QbFeeInvoice.period}}
                               AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                               -- Paid early: on an invoice of its own, not the month's.
                               AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} o
                                               WHERE o.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)))
LEFT JOIN {{QbPublication}} pb ON c.{{QbFeeCharge.kind}} IN ('AUDIENCE', 'DISTRIBUTION', 'EXPOSURE')
    AND CAST(pb.{{QbPublication.publicationId}} AS text) = split_part(c.{{QbFeeCharge.sourceKey}}, ':', 2)
WHERE i.{{QbFeeInvoice.invoiceId}} = :invoiceId
