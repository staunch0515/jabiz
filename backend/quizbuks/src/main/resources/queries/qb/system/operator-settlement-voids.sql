/*---
id: qb.system.operator-settlement-voids
description: "The counted fees whose invoice was voided since (F-M12 §3.5): a VOID line is written for each, at the rates its CHARGE line was counted at, with no VOID line yet"
entities: [QbFeeCharge, QbFeeInvoice, QbOperatorSettlementLine]
datasets:
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbOperatorSettlementLine: urn:jabiz:dataset:default:QbOperatorSettlementLine
params:
  operatorId: { like: QbFeeCharge.operatorId, required: true, description: The operator }
  maxVoids:   { kind: { type: numeric, precision: 9, scale: 0 }, required: true, description: "At most this many voids, the oldest fee first (the cap on a settlement's lines, plus one)" }
results:
  chargeId:     { from: QbFeeCharge.chargeId }
  sponsorId:    { from: QbFeeCharge.sponsorId }
  kind:         { from: QbFeeCharge.kind }
  period:       { from: QbFeeCharge.period }
  amount:       { from: QbFeeCharge.amount }
  excluded:     { from: QbOperatorSettlementLine.excluded }
  shareRate:    { from: QbOperatorSettlementLine.shareRate }
  recoveryRate: { from: QbOperatorSettlementLine.recoveryRate }
  chargedAt:    { from: QbFeeCharge.chargedAt }
permissions: [qb.operator.settlement.system]
---*/
-- A fee with a CHARGE line (counted for this operator) whose invoice is voided now and no VOID line yet. The void is
-- taken at the CHARGE line's rates (the operator may have changed plan since), so they ride along.
-- Bounded (F-M12 b, review H1): at most maxVoids, the oldest fee first (chargedAt, charge id in the C collation, as the caller
-- sorts); the rest are taken by a later settlement (a fee with a VOID line is not read again).
WITH voided AS (
SELECT c.{{QbFeeCharge.chargeId}} AS chargeId,
       c.{{QbFeeCharge.sponsorId}} AS sponsorId,
       c.{{QbFeeCharge.kind}} AS kind,
       c.{{QbFeeCharge.period}} AS period,
       c.{{QbFeeCharge.amount}} AS amount,
       l.{{QbOperatorSettlementLine.excluded}} AS excluded,
       l.{{QbOperatorSettlementLine.shareRate}} AS shareRate,
       l.{{QbOperatorSettlementLine.recoveryRate}} AS recoveryRate,
       c.{{QbFeeCharge.chargedAt}} AS chargedAt
FROM {{QbFeeCharge}} c
JOIN {{QbOperatorSettlementLine}} l ON l.{{QbOperatorSettlementLine.lineKind}} = 'CHARGE'
    AND l.{{QbOperatorSettlementLine.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)
WHERE c.{{QbFeeCharge.operatorId}} = :operatorId
  AND EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
              WHERE i.{{QbFeeInvoice.status}} = 'VOID'
                AND ((i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))
                     OR (i.{{QbFeeInvoice.chargeId}} IS NULL
                         AND i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                         AND i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}}
                         AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                         AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} o
                                         WHERE o.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)))))
  AND NOT EXISTS (SELECT 1 FROM {{QbOperatorSettlementLine}} v
                  WHERE v.{{QbOperatorSettlementLine.lineKind}} = 'VOID'
                    AND v.{{QbOperatorSettlementLine.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))
)
SELECT v.chargeId, v.sponsorId, v.kind, v.period, v.amount, v.excluded, v.shareRate, v.recoveryRate, v.chargedAt FROM (
    SELECT v0.*, row_number() OVER (ORDER BY v0.chargedAt, CAST(v0.chargeId AS text) COLLATE "C") AS seq FROM voided v0
) v
WHERE v.seq <= :maxVoids
