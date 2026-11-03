/*---
id: qb.admin.fee-charges
description: The platform fees charged (Q87), newest first, with the sponsor and the invoice's state - for finance
entities: [QbFeeCharge, QbSponsor, QbFeeInvoice]
datasets:
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
results:
  chargeId:      { from: QbFeeCharge.chargeId }
  sponsorId:     { from: QbFeeCharge.sponsorId }
  company:       { from: QbSponsor.company }
  operatorId:    { from: QbFeeCharge.operatorId }
  kind:          { from: QbFeeCharge.kind }
  period:        { from: QbFeeCharge.period }
  billingMode:   { from: QbFeeCharge.billingMode }
  amount:        { from: QbFeeCharge.amount }
  tax:           { from: QbFeeCharge.tax }
  total:         { from: QbFeeCharge.total }
  reason:        { from: QbFeeCharge.reason }
  chargedAt:     { from: QbFeeCharge.chargedAt }
  chargedBy:     { from: QbFeeCharge.chargedBy }
  invoiceNo:     { from: QbFeeInvoice.invoiceNo }
  invoiceStatus: { from: QbFeeInvoice.status }
list:
  filters: [sponsorId, operatorId, kind, period, chargedAt]
  sorts:   [chargedAt, total]
  defaultSort: { field: chargedAt, asc: false }
  key: [chargeId]
permissions: [qb.admin.finance.read]
---*/
SELECT
    c.{{QbFeeCharge.chargeId}}    AS chargeId,
    c.{{QbFeeCharge.sponsorId}}   AS sponsorId,
    s.{{QbSponsor.company}}       AS company,
    c.{{QbFeeCharge.operatorId}}  AS operatorId,
    c.{{QbFeeCharge.kind}}        AS kind,
    c.{{QbFeeCharge.period}}      AS period,
    c.{{QbFeeCharge.billingMode}} AS billingMode,
    c.{{QbFeeCharge.amount}}      AS amount,
    c.{{QbFeeCharge.tax}}         AS tax,
    c.{{QbFeeCharge.total}}       AS total,
    c.{{QbFeeCharge.reason}}      AS reason,
    c.{{QbFeeCharge.chargedAt}}   AS chargedAt,
    c.{{QbFeeCharge.chargedBy}}   AS chargedBy,
    i.{{QbFeeInvoice.invoiceNo}}  AS invoiceNo,
    i.{{QbFeeInvoice.status}}     AS invoiceStatus
FROM {{QbFeeCharge}} c
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = c.{{QbFeeCharge.sponsorId}}
LEFT JOIN {{QbFeeInvoice}} i
       ON (i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))
       OR (i.{{QbFeeInvoice.chargeId}} IS NULL AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
           AND i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
           AND i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}})
