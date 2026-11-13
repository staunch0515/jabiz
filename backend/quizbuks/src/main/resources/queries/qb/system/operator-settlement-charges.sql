/*---
id: qb.system.operator-settlement-charges
description: "The fees to count in an operator's settlement (F-M12 §3.2): its fees from the switch point on, of the month or earlier, with no CHARGE line yet and no voided invoice; every kind, late ones marked; the oldest first, at most maxFees, with how many wait in all and the settings of the month a capped settlement left them in"
entities: [QbFeeCharge, QbFeeInvoice, QbOperatorSettlementLine, QbOperatorSettlement]
datasets:
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbOperatorSettlementLine: urn:jabiz:dataset:default:QbOperatorSettlementLine
  QbOperatorSettlement: urn:jabiz:dataset:default:QbOperatorSettlement
params:
  operatorId:  { like: QbFeeCharge.operatorId, required: true, description: The operator }
  coveredFrom: { like: QbFeeCharge.chargedAt, required: true, description: "The switch point: fees charged before it are the old rule's" }
  month:       { like: QbFeeCharge.period, required: true, description: "The settlement's month (yyyy-MM): this month or earlier" }
  maxFees:     { kind: { type: numeric, precision: 9, scale: 0 }, required: true, description: "At most this many fees, the oldest first (the cap on a settlement's lines, plus one)" }
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
  waitingTotal:  { kind: { type: numeric, precision: 9, scale: 0 } }
  originMonth:   { kind: { type: text, maxLength: 7 } }
  originShare:   { from: QbOperatorSettlement.platformShare }
  originTerms:   { from: QbOperatorSettlement.cappedTerms }
permissions: [qb.operator.settlement.system]
---*/
-- Each fee counted exactly once: no CHARGE line for it yet (every kind is written, so adding a kind to the base later
-- does not sweep in the history). A fee whose invoice (its own or its month's) is voided counts no more; one voided
-- after it was counted gets a VOID line (another template). A fee of an earlier month is late and uses this month's
-- rate.
-- The cap (F-M12 b, plan §3.9 option B): the fees are numbered oldest first (chargedAt, then the charge id in the C
-- collation; the caller sorts the same way) and only the first `maxFees` rows come back, with how many wait in all (`waitingTotal`):
-- the caller asks for the cap plus one (maxFees), so a cap that cuts is seen. originMonth: the earliest capped settlement of this
-- operator that left the fee (it was a candidate then: of its month or earlier, charged before it was issued, after
-- its watermark); originShare and originTerms are that settlement's plan share and settings, at which the fee is
-- counted later. None: the fee is counted at this month's. (The same expression is in qb.system.operator-credit-notes.)
WITH waiting AS (
    SELECT c.{{QbFeeCharge.chargeId}} AS charge_id,
           c.{{QbFeeCharge.sponsorId}} AS sponsor_id,
           c.{{QbFeeCharge.kind}} AS kind,
           c.{{QbFeeCharge.period}} AS period,
           c.{{QbFeeCharge.amount}} AS amount,
           c.{{QbFeeCharge.shareExcluded}} AS share_excluded,
           c.{{QbFeeCharge.total}} AS total,
           c.{{QbFeeCharge.chargedAt}} AS charged_at,
           (c.{{QbFeeCharge.period}} < :month) AS late,
           (SELECT min(s.{{QbOperatorSettlement.month}}) FROM {{QbOperatorSettlement}} s
             WHERE s.{{QbOperatorSettlement.operatorId}} = :operatorId
               AND s.{{QbOperatorSettlement.capped}}
               AND c.{{QbFeeCharge.period}} <= s.{{QbOperatorSettlement.month}}
               AND c.{{QbFeeCharge.chargedAt}} < s.{{QbOperatorSettlement.issuedAt}}
               AND (s.{{QbOperatorSettlement.watermarkChargedAt}} IS NULL
                    OR c.{{QbFeeCharge.chargedAt}} > s.{{QbOperatorSettlement.watermarkChargedAt}}
                    OR (c.{{QbFeeCharge.chargedAt}} = s.{{QbOperatorSettlement.watermarkChargedAt}}
                        AND CAST(c.{{QbFeeCharge.chargeId}} AS text) COLLATE "C"
                            > s.{{QbOperatorSettlement.watermarkChargeId}} COLLATE "C"))) AS origin_month
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
), numbered AS (
    SELECT w.*, row_number() OVER (ORDER BY w.charged_at, CAST(w.charge_id AS text) COLLATE "C") AS seq,
           count(*) OVER () AS all_waiting
    FROM waiting w
)
SELECT n.charge_id AS chargeId, n.sponsor_id AS sponsorId, n.kind AS kind, n.period AS period, n.amount AS amount,
       n.share_excluded AS shareExcluded, n.total AS total, n.charged_at AS chargedAt, n.late AS late,
       n.all_waiting AS waitingTotal, n.origin_month AS originMonth,
       o.{{QbOperatorSettlement.platformShare}} AS originShare, o.{{QbOperatorSettlement.cappedTerms}} AS originTerms
FROM numbered n
LEFT JOIN {{QbOperatorSettlement}} o ON o.{{QbOperatorSettlement.operatorId}} = :operatorId
    AND o.{{QbOperatorSettlement.month}} = n.origin_month
WHERE n.seq <= :maxFees
