/*---
id: qb.system.fee-invoices-due
description: "Q87b: the sponsors with charges in a month and no invoice of it yet (the invoice job's items)"
entities: [QbFeeCharge, QbFeeInvoice]
datasets:
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
params:
  period: { like: QbFeeCharge.period, required: true, description: "The month (YYYY-MM, Japan)" }
results:
  sponsorId: { from: QbFeeCharge.sponsorId }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.fee.system]
---*/
SELECT DISTINCT c.{{QbFeeCharge.sponsorId}} AS sponsorId
FROM {{QbFeeCharge}} c
WHERE c.{{QbFeeCharge.period}} = :period
  AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i WHERE i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
      AND i.{{QbFeeInvoice.period}} = :period)
