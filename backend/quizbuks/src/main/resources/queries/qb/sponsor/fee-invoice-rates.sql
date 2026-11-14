/*---
id: qb.sponsor.fee-invoice-rates
description: "F-M13: one invoice of the sponsor's own by tax rate - the fees before tax, the tax and the total of each rate (a month whose rate changed has two) - the document's subtotals"
entities: [QbFeeInvoice, QbFeeCharge]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:sponsor:QbFeeInvoice
  QbFeeCharge: urn:jabiz:dataset:sponsor:QbFeeCharge
params:
  invoiceId: { like: QbFeeInvoice.invoiceId, required: true, description: The invoice }
results:
  taxRate:   { from: QbFeeCharge.taxRate }
  rateNet:   { kind: { type: monetary, currency: JPY, scale: 0 } }
  rateTax:   { kind: { type: monetary, currency: JPY, scale: 0 } }
  rateGross: { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [taxRate]
  defaultSort: { field: taxRate, asc: false }
  key: [taxRate]
permissions: [qb.topup]
---*/
-- The same charges as qb.sponsor.fee-invoice-lines (CHANGE BOTH: the lines and the subtotals of one invoice are the same
-- set), grouped by the rate they were charged at. The tax of each charge is its own (rounded down per charge, as the
-- fee account does), so a subtotal's tax is the sum of the charges' taxes, not a recalculation.
SELECT
    c.{{QbFeeCharge.taxRate}}     AS taxRate,
    sum(c.{{QbFeeCharge.amount}}) AS rateNet,
    sum(c.{{QbFeeCharge.tax}})    AS rateTax,
    sum(c.{{QbFeeCharge.total}})  AS rateGross
FROM {{QbFeeInvoice}} i
JOIN {{QbFeeCharge}} c ON (i.{{QbFeeInvoice.chargeId}} IS NOT NULL
                               AND CAST(c.{{QbFeeCharge.chargeId}} AS text) = i.{{QbFeeInvoice.chargeId}})
                       OR (i.{{QbFeeInvoice.chargeId}} IS NULL
                               AND c.{{QbFeeCharge.sponsorId}} = i.{{QbFeeInvoice.sponsorId}}
                               AND c.{{QbFeeCharge.period}} = i.{{QbFeeInvoice.period}}
                               AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                               AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} o
                                               WHERE o.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)))
WHERE i.{{QbFeeInvoice.invoiceId}} = :invoiceId
GROUP BY c.{{QbFeeCharge.taxRate}}
