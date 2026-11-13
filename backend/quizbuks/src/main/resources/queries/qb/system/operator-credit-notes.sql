/*---
id: qb.system.operator-credit-notes
description: "The credit notes issued in a month on an operator's fees (F-M7 C3, before tax) with what the fee's CHARGE line says of its base and rate (F-M12 §3.5): the operator gives back its share of the part of the fee that entered the base"
entities: [QbFeeCreditNote, QbFeeCharge, QbOperatorSettlementLine, QbOperatorSettlement]
datasets:
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbOperatorSettlementLine: urn:jabiz:dataset:default:QbOperatorSettlementLine
  QbOperatorSettlement: urn:jabiz:dataset:default:QbOperatorSettlement
params:
  operatorId: { like: QbFeeCreditNote.operatorId, required: true, description: The operator }
  from:       { like: QbFeeCreditNote.issuedAt, required: true, description: "The month's first instant" }
  to:         { like: QbFeeCreditNote.issuedAt, required: true, description: "The next month's first instant" }
results:
  creditNoteId: { kind: { type: text, maxLength: 36 } }
  chargeId:     { from: QbFeeCreditNote.chargeId }
  amount:       { from: QbFeeCreditNote.amount }
  feeKind:      { from: QbFeeCharge.kind }
  chargeAmount: { from: QbFeeCharge.amount }
  chargeShareExcluded: { from: QbFeeCharge.shareExcluded }
  lineAmount:   { from: QbOperatorSettlementLine.amount }
  lineExcluded: { from: QbOperatorSettlementLine.excluded }
  lineShareRate: { from: QbOperatorSettlementLine.shareRate }
  originShare:  { from: QbOperatorSettlement.platformShare }
  originTerms:  { from: QbOperatorSettlement.cappedTerms }
permissions: [qb.operator.settlement.system]
---*/
-- One row a note. The fee's CHARGE line (when it was counted) says how much of it entered the base and at which share:
-- the note takes back that part of its amount at that rate. A fee not counted yet (or counted by the old rule) has no
-- line: the caller works the part out from the fee and the settings of the month, or, when a capped settlement left the
-- fee (F-M12 b: it is counted later at that settlement's share and settings), at those: the note is taken at the rates
-- the fee will be counted at, once, in the month it was issued. originMonth is worked out as in
-- qb.system.operator-settlement-charges.
SELECT CAST(n.{{QbFeeCreditNote.creditNoteId}} AS text) AS creditNoteId,
       n.{{QbFeeCreditNote.chargeId}} AS chargeId,
       n.{{QbFeeCreditNote.amount}} AS amount,
       c.{{QbFeeCharge.kind}} AS feeKind,
       c.{{QbFeeCharge.amount}} AS chargeAmount,
       c.{{QbFeeCharge.shareExcluded}} AS chargeShareExcluded,
       l.{{QbOperatorSettlementLine.amount}} AS lineAmount,
       l.{{QbOperatorSettlementLine.excluded}} AS lineExcluded,
       l.{{QbOperatorSettlementLine.shareRate}} AS lineShareRate,
       o.{{QbOperatorSettlement.platformShare}} AS originShare,
       o.{{QbOperatorSettlement.cappedTerms}} AS originTerms
FROM {{QbFeeCreditNote}} n
JOIN {{QbFeeCharge}} c ON CAST(c.{{QbFeeCharge.chargeId}} AS text) = n.{{QbFeeCreditNote.chargeId}}
LEFT JOIN {{QbOperatorSettlementLine}} l ON l.{{QbOperatorSettlementLine.lineKind}} = 'CHARGE'
    AND l.{{QbOperatorSettlementLine.chargeId}} = n.{{QbFeeCreditNote.chargeId}}
LEFT JOIN {{QbOperatorSettlement}} o ON l.{{QbOperatorSettlementLine.lineId}} IS NULL
    AND o.{{QbOperatorSettlement.operatorId}} = :operatorId
    AND o.{{QbOperatorSettlement.month}} = (SELECT min(s.{{QbOperatorSettlement.month}}) FROM {{QbOperatorSettlement}} s
        WHERE s.{{QbOperatorSettlement.operatorId}} = :operatorId
          AND s.{{QbOperatorSettlement.capped}}
          AND c.{{QbFeeCharge.period}} <= s.{{QbOperatorSettlement.month}}
          AND c.{{QbFeeCharge.chargedAt}} < s.{{QbOperatorSettlement.issuedAt}}
          AND (s.{{QbOperatorSettlement.watermarkChargedAt}} IS NULL
               OR c.{{QbFeeCharge.chargedAt}} > s.{{QbOperatorSettlement.watermarkChargedAt}}
               OR (c.{{QbFeeCharge.chargedAt}} = s.{{QbOperatorSettlement.watermarkChargedAt}}
                   AND CAST(c.{{QbFeeCharge.chargeId}} AS text) COLLATE "C"
                       > s.{{QbOperatorSettlement.watermarkChargeId}} COLLATE "C")))
WHERE n.{{QbFeeCreditNote.operatorId}} = :operatorId
  AND n.{{QbFeeCreditNote.issuedAt}} >= :from
  AND n.{{QbFeeCreditNote.issuedAt}} < :to
