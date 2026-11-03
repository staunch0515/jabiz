/*---
id: qb.system.fee-uninvoiced
description: "Q87b: what a sponsor owes of each month not invoiced yet (receivable charges with their tax, less what was paid early)"
entities: [QbFeeCharge, QbFeeInvoice, QbFeePayment]
datasets:
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
params:
  sponsorId: { like: QbFeeCharge.sponsorId, required: true, description: The sponsor }
results:
  period: { from: QbFeeCharge.period }
  owed:   { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [period]
  defaultSort: { field: period, asc: true }
  key: [period]
permissions: [qb.fee.system]
---*/
SELECT c.period AS period, c.owed - coalesce(p.paid, 0) AS owed
FROM (
    SELECT x.{{QbFeeCharge.period}} AS period, sum(x.{{QbFeeCharge.amount}} + x.{{QbFeeCharge.tax}}) AS owed
    FROM {{QbFeeCharge}} x
    WHERE x.{{QbFeeCharge.sponsorId}} = :sponsorId AND x.{{QbFeeCharge.settlement}} = 'RECEIVABLE'
      AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i WHERE i.{{QbFeeInvoice.sponsorId}} = :sponsorId
          AND i.{{QbFeeInvoice.period}} = x.{{QbFeeCharge.period}})
    GROUP BY x.{{QbFeeCharge.period}}) c
LEFT JOIN (
    SELECT y.{{QbFeePayment.period}} AS period, sum(y.{{QbFeePayment.amount}}) AS paid
    FROM {{QbFeePayment}} y
    WHERE y.{{QbFeePayment.sponsorId}} = :sponsorId AND y.{{QbFeePayment.invoiceId}} IS NULL
    GROUP BY y.{{QbFeePayment.period}}) p ON p.period = c.period
WHERE c.owed - coalesce(p.paid, 0) > 0
