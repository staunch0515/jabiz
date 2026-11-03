/*---
id: qb.sponsor.fee-uninvoiced
description: The sponsor's postpaid platform fees not on an invoice yet (Q87b) - what paying early settles, oldest first
entities: [QbFeeCharge, QbFeeInvoice]
datasets:
  QbFeeCharge: urn:jabiz:dataset:sponsor:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:sponsor:QbFeeInvoice
params:
  period: { like: QbFeeCharge.period, description: "Only this month's (yyyy-MM); none: every month's" }
results:
  chargeId:  { from: QbFeeCharge.chargeId }
  kind:      { from: QbFeeCharge.kind }
  period:    { from: QbFeeCharge.period }
  amount:    { from: QbFeeCharge.amount }
  tax:       { from: QbFeeCharge.tax }
  total:     { from: QbFeeCharge.total }
  chargedAt: { from: QbFeeCharge.chargedAt }
list:
  sorts: [chargedAt]
  defaultSort: { field: chargedAt, asc: true }
  key: [chargeId]
permissions: [qb.sponsor.finance.read]
---*/
-- A postpaid charge is on its month's invoice (the job's, naming no charge) or on one of its own (paid early).
SELECT
    c.{{QbFeeCharge.chargeId}}  AS chargeId,
    c.{{QbFeeCharge.kind}}      AS kind,
    c.{{QbFeeCharge.period}}    AS period,
    c.{{QbFeeCharge.amount}}    AS amount,
    c.{{QbFeeCharge.tax}}       AS tax,
    c.{{QbFeeCharge.total}}     AS total,
    c.{{QbFeeCharge.chargedAt}} AS chargedAt
FROM {{QbFeeCharge}} c
WHERE c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
  AND (CAST(:period AS text) IS NULL OR c.{{QbFeeCharge.period}} = CAST(:period AS text))
  AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
                  WHERE i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)
                     OR (i.{{QbFeeInvoice.chargeId}} IS NULL AND i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}}))
