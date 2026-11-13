/*---
id: qb.system.operator-fees
description: "The platform fees (before tax) charged in a period to the sponsors an operator ran when charged (Q87, D-Q87-8), those of voided invoices left out; and the credit notes issued on that operator's fees in the period (F-M7, before tax)"
entities: [QbFeeCharge, QbFeeInvoice, QbFeeCreditNote]
datasets:
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
params:
  operatorId: { like: QbFeeCharge.operatorId, required: true, description: The operator }
  from:       { like: QbFeeCharge.chargedAt, required: true, description: "The period's first instant" }
  to:         { like: QbFeeCharge.chargedAt, required: true, description: "The next period's first instant" }
results:
  fees:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  lines: { kind: { type: numeric, precision: 12, scale: 0 } }
  creditNotes: { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.operator.settlement.system]
---*/
-- Always one row. The charge keeps the operator it was made under, as a card keeps the operator it went on under;
-- the settlement shares the fee itself (the tax is the platform's to pay on). A charge whose invoice is voided (its
-- own, or its month's) counts no more. F-M7 (C3): the credit notes issued in the period on the operator's fees, before
-- tax, whenever the fee was charged; the settlement takes the operator's share of them back.
SELECT coalesce(sum(c.{{QbFeeCharge.amount}}), 0) AS fees, count(c.{{QbFeeCharge.chargeId}}) AS lines,
       (SELECT coalesce(sum(n.{{QbFeeCreditNote.amount}}), 0) FROM {{QbFeeCreditNote}} n
        WHERE n.{{QbFeeCreditNote.operatorId}} = :operatorId
          AND n.{{QbFeeCreditNote.issuedAt}} >= :from
          AND n.{{QbFeeCreditNote.issuedAt}} < :to) AS creditNotes
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
                             AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                             -- Paid early: on an invoice of its own, not the month's.
                             AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} o
                                             WHERE o.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)))))
