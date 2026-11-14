/*---
id: qb.system.money-close-uninvoiced
description: "An erased sponsor's months with postpaid platform fees and no invoice of them yet, the current month included (F-M15 b §5.2 step 5): the wind-up invoices them all once nothing is held any more"
entities: [QbFeeCharge, QbFeeInvoice]
datasets:
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
params:
  sponsorId: { like: QbFeeCharge.sponsorId, required: true, description: The erased sponsor }
results:
  period: { from: QbFeeCharge.period }
permissions: [qb.fee.system]
---*/
-- The owed part of qb.system.fee-invoices-due, without its two waits: every month (the current one too: nothing more
-- will be charged in it once the account is wound up) and no wait for settlements (the wind-up runs only when no
-- prepayment is held any more, so every distribution and exposure fee is settled). The caller orders them.
SELECT c.{{QbFeeCharge.period}} AS period
FROM {{QbFeeCharge}} c
WHERE c.{{QbFeeCharge.sponsorId}} = :sponsorId
  AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
  AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
                  WHERE i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                    AND ((i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}} AND i.{{QbFeeInvoice.chargeId}} IS NULL)
                         -- Paid early: on an invoice of its own.
                         OR i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)))
GROUP BY c.{{QbFeeCharge.period}}
