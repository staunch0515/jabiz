/*---
id: qb.sponsor.fee-charges
description: The platform fees charged to the sponsor (Q87), newest first, each with its invoice and the invoice's state
entities: [QbFeeCharge, QbFeeInvoice]
datasets:
  QbFeeCharge: urn:jabiz:dataset:sponsor:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:sponsor:QbFeeInvoice
results:
  chargeId:      { from: QbFeeCharge.chargeId }
  kind:          { from: QbFeeCharge.kind }
  period:        { from: QbFeeCharge.period }
  billingMode:   { from: QbFeeCharge.billingMode }
  amount:        { from: QbFeeCharge.amount }
  tax:           { from: QbFeeCharge.tax }
  total:         { from: QbFeeCharge.total }
  reason:        { from: QbFeeCharge.reason }
  chargedAt:     { from: QbFeeCharge.chargedAt }
  invoiceId:     { from: QbFeeInvoice.invoiceId }
  invoiceNo:     { from: QbFeeInvoice.invoiceNo }
  invoiceStatus: { from: QbFeeInvoice.status }
list:
  filters: [kind, period, chargedAt]
  sorts:   [chargedAt, total]
  defaultSort: { field: chargedAt, asc: false }
  key: [chargeId]
permissions: [qb.sponsor.finance.read]
---*/
-- A prepaid charge's invoice is its own; a postpaid charge's is its month's (none until the month is invoiced).
SELECT
    c.{{QbFeeCharge.chargeId}}    AS chargeId,
    c.{{QbFeeCharge.kind}}        AS kind,
    c.{{QbFeeCharge.period}}      AS period,
    c.{{QbFeeCharge.billingMode}} AS billingMode,
    c.{{QbFeeCharge.amount}}      AS amount,
    c.{{QbFeeCharge.tax}}         AS tax,
    c.{{QbFeeCharge.total}}       AS total,
    c.{{QbFeeCharge.reason}}      AS reason,
    c.{{QbFeeCharge.chargedAt}}   AS chargedAt,
    i.{{QbFeeInvoice.invoiceId}}  AS invoiceId,
    i.{{QbFeeInvoice.invoiceNo}}  AS invoiceNo,
    i.{{QbFeeInvoice.status}}     AS invoiceStatus
FROM {{QbFeeCharge}} c
LEFT JOIN {{QbFeeInvoice}} i
       ON (i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))
       OR (i.{{QbFeeInvoice.chargeId}} IS NULL AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
           AND i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}}
           AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} o
                           WHERE o.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)))
