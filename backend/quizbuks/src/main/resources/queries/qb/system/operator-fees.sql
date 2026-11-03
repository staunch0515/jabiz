/*---
id: qb.system.operator-fees
description: "The platform fees (before tax) charged in a period to the sponsors an operator ran when charged (Q87, D-Q87-8), those of voided invoices left out"
entities: [QbFeeCharge, QbFeeInvoice]
datasets:
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
params:
  operatorId: { like: QbFeeCharge.operatorId, required: true, description: The operator }
  from:       { like: QbFeeCharge.chargedAt, required: true, description: "The period's first instant" }
  to:         { like: QbFeeCharge.chargedAt, required: true, description: "The next period's first instant" }
results:
  fees:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  lines: { kind: { type: numeric, precision: 12, scale: 0 } }
permissions: [qb.operator.settlement.system]
---*/
-- Always one row. The charge keeps the operator it was made under, as a card keeps the operator it went on under;
-- the settlement shares the fee itself (the tax is the platform's to pay on). A charge whose invoice is voided (its
-- own, or its month's) counts no more.
SELECT coalesce(sum(c.{{QbFeeCharge.amount}}), 0) AS fees, count(c.{{QbFeeCharge.chargeId}}) AS lines
FROM {{QbFeeCharge}} c
WHERE c.{{QbFeeCharge.operatorId}} = :operatorId
  AND c.{{QbFeeCharge.chargedAt}} >= :from
  AND c.{{QbFeeCharge.chargedAt}} < :to
  AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
                  WHERE i.{{QbFeeInvoice.status}} = 'VOID'
                    AND ((i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))
                         OR (i.{{QbFeeInvoice.chargeId}} IS NULL
                             AND i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                             AND i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}}
                             AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID')))
