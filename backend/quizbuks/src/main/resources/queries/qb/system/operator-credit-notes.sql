/*---
id: qb.system.operator-credit-notes
description: "The credit notes issued in a month on an operator's fees (F-M7 C3, before tax) with what the fee's CHARGE line says of its base and rate (F-M12 §3.5): the operator gives back its share of the part of the fee that entered the base"
entities: [QbFeeCreditNote, QbFeeCharge, QbOperatorSettlementLine]
datasets:
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbOperatorSettlementLine: urn:jabiz:dataset:default:QbOperatorSettlementLine
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
permissions: [qb.operator.settlement.system]
---*/
-- One row a note. The fee's CHARGE line (when it was counted) says how much of it entered the base and at which share:
-- the note takes back that part of its amount at that rate. A fee not counted yet (or counted by the old rule) has no
-- line: the caller works the part out from the fee and the settings of the month.
SELECT CAST(n.{{QbFeeCreditNote.creditNoteId}} AS text) AS creditNoteId,
       n.{{QbFeeCreditNote.chargeId}} AS chargeId,
       n.{{QbFeeCreditNote.amount}} AS amount,
       c.{{QbFeeCharge.kind}} AS feeKind,
       c.{{QbFeeCharge.amount}} AS chargeAmount,
       c.{{QbFeeCharge.shareExcluded}} AS chargeShareExcluded,
       l.{{QbOperatorSettlementLine.amount}} AS lineAmount,
       l.{{QbOperatorSettlementLine.excluded}} AS lineExcluded,
       l.{{QbOperatorSettlementLine.shareRate}} AS lineShareRate
FROM {{QbFeeCreditNote}} n
JOIN {{QbFeeCharge}} c ON CAST(c.{{QbFeeCharge.chargeId}} AS text) = n.{{QbFeeCreditNote.chargeId}}
LEFT JOIN {{QbOperatorSettlementLine}} l ON l.{{QbOperatorSettlementLine.lineKind}} = 'CHARGE'
    AND l.{{QbOperatorSettlementLine.chargeId}} = n.{{QbFeeCreditNote.chargeId}}
WHERE n.{{QbFeeCreditNote.operatorId}} = :operatorId
  AND n.{{QbFeeCreditNote.issuedAt}} >= :from
  AND n.{{QbFeeCreditNote.issuedAt}} < :to
