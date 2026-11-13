/*---
id: qb.admin.fee-charge-credit
description: "F-M7 (§10.3-12): what a fee may still be credited by a credit note by hand - its amount and tax, its notes so far, and its invoice"
entities: [QbFeeCharge, QbFeeCreditNote, QbFeeInvoice, QbSponsor]
datasets:
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  chargeId: { like: QbFeeCharge.chargeId, required: true, description: The fee }
results:
  chargeId:      { from: QbFeeCharge.chargeId }
  sponsorId:     { from: QbFeeCharge.sponsorId }
  company:       { from: QbSponsor.company }
  kind:          { from: QbFeeCharge.kind }
  period:        { from: QbFeeCharge.period }
  billingMode:   { from: QbFeeCharge.billingMode }
  amount:        { from: QbFeeCharge.amount }
  taxRate:       { from: QbFeeCharge.taxRate }
  tax:           { from: QbFeeCharge.tax }
  total:         { from: QbFeeCharge.total }
  credited:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  creditedTax:   { kind: { type: monetary, currency: JPY, scale: 0 } }
  creditable:    { kind: { type: monetary, currency: JPY, scale: 0 } }
  taxLeft:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  notes:         { kind: { type: numeric, precision: 9, scale: 0 } }
  invoiceNo:     { from: QbFeeInvoice.invoiceNo }
  invoiceStatus: { from: QbFeeInvoice.status }
permissions: [qb.admin.finance.read]
---*/
-- At most one row. The fee's invoice: its own (prepaid, or paid early), else its month's (postpaid); a voided one is
-- not credited (QB_FEE_CREDIT_NOTE_VOIDED). Creditable: its amount less its notes' amounts; the tax left is what the
-- note that credits the rest takes.
SELECT
    c.{{QbFeeCharge.chargeId}}    AS chargeId,
    c.{{QbFeeCharge.sponsorId}}   AS sponsorId,
    s.{{QbSponsor.company}}       AS company,
    c.{{QbFeeCharge.kind}}        AS kind,
    c.{{QbFeeCharge.period}}      AS period,
    c.{{QbFeeCharge.billingMode}} AS billingMode,
    c.{{QbFeeCharge.amount}}      AS amount,
    c.{{QbFeeCharge.taxRate}}     AS taxRate,
    c.{{QbFeeCharge.tax}}         AS tax,
    c.{{QbFeeCharge.total}}       AS total,
    coalesce(n.r_amount, 0)       AS credited,
    coalesce(n.r_tax, 0)          AS creditedTax,
    greatest(c.{{QbFeeCharge.amount}} - coalesce(n.r_amount, 0), 0) AS creditable,
    greatest(c.{{QbFeeCharge.tax}} - coalesce(n.r_tax, 0), 0)       AS taxLeft,
    coalesce(n.r_count, 0)        AS notes,
    coalesce(own.{{QbFeeInvoice.invoiceNo}}, mon.{{QbFeeInvoice.invoiceNo}}) AS invoiceNo,
    coalesce(own.{{QbFeeInvoice.status}}, mon.{{QbFeeInvoice.status}})       AS invoiceStatus
FROM {{QbFeeCharge}} c
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = c.{{QbFeeCharge.sponsorId}}
LEFT JOIN (SELECT x.{{QbFeeCreditNote.chargeId}} AS r_charge, sum(x.{{QbFeeCreditNote.amount}}) AS r_amount,
                  sum(x.{{QbFeeCreditNote.tax}}) AS r_tax, count(*) AS r_count
           FROM {{QbFeeCreditNote}} x GROUP BY x.{{QbFeeCreditNote.chargeId}}) n
       ON n.r_charge = CAST(c.{{QbFeeCharge.chargeId}} AS text)
LEFT JOIN {{QbFeeInvoice}} own ON own.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)
LEFT JOIN {{QbFeeInvoice}} mon ON own.{{QbFeeInvoice.invoiceId}} IS NULL
       AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
       AND mon.{{QbFeeInvoice.chargeId}} IS NULL
       AND mon.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
       AND mon.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}}
WHERE c.{{QbFeeCharge.chargeId}} = :chargeId
