/*---
id: qb.system.fee-invoices-due
description: The sponsors with postpaid platform fees of a month and no invoice of it yet (the items of the job qb.fee-invoice, Q87)
entities: [QbFeeCharge, QbFeeInvoice]
datasets:
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
params:
  period: { like: QbFeeCharge.period, required: true, description: "The month ended (yyyy-MM, Japan's)" }
results:
  sponsorId: { from: QbFeeCharge.sponsorId }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.fee.system]
---*/
-- The item reads again under the sponsor's wallet lock and issues nothing when the month's invoice is there meanwhile.
SELECT DISTINCT c.{{QbFeeCharge.sponsorId}} AS sponsorId
FROM {{QbFeeCharge}} c
WHERE c.{{QbFeeCharge.period}} = :period
  AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
  AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
                  WHERE i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                    AND i.{{QbFeeInvoice.period}} = :period
                    AND i.{{QbFeeInvoice.chargeId}} IS NULL)
