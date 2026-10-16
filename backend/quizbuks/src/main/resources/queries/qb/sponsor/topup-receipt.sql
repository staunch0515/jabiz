/*---
id: qb.sponsor.topup-receipt
description: One paid top-up of the sponsor's own, as its receipt shows it (Q9d, F-09)
entities: [QbTopUp, QbSponsor]
datasets:
  QbTopUp: urn:jabiz:dataset:sponsor:QbTopUp
  QbSponsor: urn:jabiz:dataset:sponsor:QbSponsor
params:
  topUpId: { like: QbTopUp.topUpId, required: true, description: The top-up }
results:
  receiptNo:        { from: QbTopUp.topUpId }
  company:          { from: QbSponsor.company }
  contactName:      { from: QbSponsor.contactName }
  paidAt:           { from: QbTopUp.paidAt }
  paymentReference: { kind: { type: text, maxLength: 20 } }
  kudos:            { kind: { type: numeric, precision: 19, scale: 0 } }
  amount:           { from: QbTopUp.amount }
permissions: [qb.topup]
---*/
-- Paid top-ups only: a receipt is for money received. The payment's reference is the end of Stripe's payment id, enough
-- for the sponsor to match their card statement's entry without the whole id. Refunds do not change the receipt.
SELECT
    t.{{QbTopUp.topUpId}} AS receiptNo,
    s.{{QbSponsor.company}} AS company,
    s.{{QbSponsor.contactName}} AS contactName,
    t.{{QbTopUp.paidAt}} AS paidAt,
    CAST('...' || right(t.{{QbTopUp.paymentIntentId}}, 8) AS varchar(20)) AS paymentReference,
    t.{{QbTopUp.amount}} AS kudos,
    t.{{QbTopUp.amount}} AS amount
FROM {{QbTopUp}} t
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = t.{{QbTopUp.sponsorId}}
WHERE t.{{QbTopUp.topUpId}} = :topUpId
  AND t.{{QbTopUp.status}} = 'PAID'
