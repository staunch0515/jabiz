/*---
id: qb.system.operator-settlement-charges
description: "The fees to count in an operator's settlement (F-M12 §3.2): its fees from the switch point on, of the month or earlier, with no CHARGE line yet and no voided invoice; every kind, late ones marked"
entities: [QbFeeCharge, QbFeeInvoice, QbOperatorSettlementLine]
datasets:
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbOperatorSettlementLine: urn:jabiz:dataset:default:QbOperatorSettlementLine
params:
  operatorId:  { like: QbFeeCharge.operatorId, required: true, description: The operator }
  coveredFrom: { like: QbFeeCharge.chargedAt, required: true, description: "The switch point: fees charged before it are the old rule's" }
  month:       { like: QbFeeCharge.period, required: true, description: "The settlement's month (yyyy-MM): this month or earlier" }
results:
  chargeId:      { from: QbFeeCharge.chargeId }
  sponsorId:     { from: QbFeeCharge.sponsorId }
  kind:          { from: QbFeeCharge.kind }
  period:        { from: QbFeeCharge.period }
  amount:        { from: QbFeeCharge.amount }
  shareExcluded: { from: QbFeeCharge.shareExcluded }
  total:         { from: QbFeeCharge.total }
  chargedAt:     { from: QbFeeCharge.chargedAt }
  late:          { kind: { type: bool } }
permissions: [qb.operator.settlement.system]
---*/
-- Each fee counted exactly once: no CHARGE line for it yet (every kind is written, so adding a kind to the base later
-- does not sweep in the history). A fee whose invoice (its own or its month's) is voided counts no more; one voided
-- after it was counted gets a VOID line (another template). A fee of an earlier month is late and uses this month's
-- rate.
SELECT c.{{QbFeeCharge.chargeId}} AS chargeId,
       c.{{QbFeeCharge.sponsorId}} AS sponsorId,
       c.{{QbFeeCharge.kind}} AS kind,
       c.{{QbFeeCharge.period}} AS period,
       c.{{QbFeeCharge.amount}} AS amount,
       c.{{QbFeeCharge.shareExcluded}} AS shareExcluded,
       c.{{QbFeeCharge.total}} AS total,
       c.{{QbFeeCharge.chargedAt}} AS chargedAt,
       (c.{{QbFeeCharge.period}} < :month) AS late
FROM {{QbFeeCharge}} c
WHERE c.{{QbFeeCharge.operatorId}} = :operatorId
  AND c.{{QbFeeCharge.chargedAt}} >= :coveredFrom
  AND c.{{QbFeeCharge.period}} <= :month
  AND NOT EXISTS (SELECT 1 FROM {{QbOperatorSettlementLine}} l
                  WHERE l.{{QbOperatorSettlementLine.lineKind}} = 'CHARGE'
                    AND l.{{QbOperatorSettlementLine.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))
  AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
                  WHERE i.{{QbFeeInvoice.status}} = 'VOID'
                    AND ((i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))
                         OR (i.{{QbFeeInvoice.chargeId}} IS NULL
                             AND i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                             AND i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}}
                             AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                             AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} o
                                             WHERE o.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)))))
