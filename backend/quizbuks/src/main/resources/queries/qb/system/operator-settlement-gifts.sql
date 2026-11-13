/*---
id: qb.system.operator-settlement-gifts
description: "The parts of payments made with granted credit to count in an operator's settlement (F-M12 §3.3): FEE_CREDIT parts from the switch point on, resolved to the fee they paid, whose fee is counted and which have no GIFT line yet"
entities: [QbFeePayment, QbFeeCharge, QbFeeInvoice, QbFeeCreditNote, QbFeeCreditGrant, QbOperatorSettlementLine]
datasets:
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbOperatorSettlementLine: urn:jabiz:dataset:default:QbOperatorSettlementLine
params:
  operatorId:  { like: QbFeeCharge.operatorId, required: true, description: The operator }
  coveredFrom: { like: QbFeePayment.paidAt, required: true, description: "The switch point: parts paid before it are the old rule's" }
  countedNow:  { kind: { type: text, maxLength: 36 }, list: true, required: true, description: "The ids of the fees counted in this settlement (none so far have a CHARGE line)" }
results:
  paymentId:          { from: QbFeePayment.paymentId }
  chargeId:           { from: QbFeeCharge.chargeId }
  targetKind:         { kind: { type: text, maxLength: 10 } }
  paymentAmount:      { from: QbFeePayment.amount }
  paidAt:             { from: QbFeePayment.paidAt }
  grantId:            { from: QbFeePayment.grantId }
  grantSource:        { from: QbFeeCreditGrant.source }
  sponsorId:          { from: QbFeeCharge.sponsorId }
  feeKind:            { from: QbFeeCharge.kind }
  period:             { from: QbFeeCharge.period }
  chargeAmount:       { from: QbFeeCharge.amount }
  chargeTotal:        { from: QbFeeCharge.total }
  chargeShareExcluded: { from: QbFeeCharge.shareExcluded }
  lineExcluded:       { from: QbOperatorSettlementLine.excluded }
  lineShareRate:      { from: QbOperatorSettlementLine.shareRate }
  lineRecoveryRate:   { from: QbOperatorSettlementLine.recoveryRate }
  priorGross:         { kind: { type: monetary, currency: JPY, scale: 0 } }
  priorOperatorGross: { kind: { type: monetary, currency: JPY, scale: 0 } }
  priorAmount:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  priorInBase:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  priorRecover:       { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.operator.settlement.system]
---*/
-- A FEE_CREDIT part aims at a fee, an invoice or a credit note (CreditNotes.recordBack writes the credit given back as a
-- negative part aimed at the note). Each is resolved to the fee(s) it paid: a charge directly, an invoice's charges
-- (each its own row, so a month's invoice of two operators' fees splits each to its own operator), a note by its fee.
-- Only the fees of this operator that are counted (have a CHARGE line, or are counted in this settlement) and parts
-- with no GIFT line yet. The CHARGE line's rates and the fee's prior gift rows ride along for the cumulative rounding.
WITH gift AS (
    SELECT p.{{QbFeePayment.paymentId}} AS payment_id, p.{{QbFeePayment.targetEntity}} AS target_entity,
           p.{{QbFeePayment.targetId}} AS target_id, p.{{QbFeePayment.amount}} AS amount,
           p.{{QbFeePayment.paidAt}} AS paid_at, p.{{QbFeePayment.grantId}} AS grant_id
    FROM {{QbFeePayment}} p
    WHERE p.{{QbFeePayment.subAccount}} = 'FEE_CREDIT'
      AND p.{{QbFeePayment.paidAt}} >= :coveredFrom
), resolved AS (
    SELECT g.payment_id, 'CHARGE' AS target_kind, CAST(c.{{QbFeeCharge.chargeId}} AS text) AS charge_id,
           g.amount, g.paid_at, g.grant_id
    FROM gift g
    JOIN {{QbFeeCharge}} c ON g.target_entity = 'QbFeeCharge' AND CAST(c.{{QbFeeCharge.chargeId}} AS text) = g.target_id
    UNION ALL
    SELECT g.payment_id, 'INVOICE', CAST(c.{{QbFeeCharge.chargeId}} AS text), g.amount, g.paid_at, g.grant_id
    FROM gift g
    JOIN {{QbFeeInvoice}} i ON g.target_entity = 'QbFeeInvoice' AND CAST(i.{{QbFeeInvoice.invoiceId}} AS text) = g.target_id
    JOIN {{QbFeeCharge}} c ON (i.{{QbFeeInvoice.chargeId}} IS NOT NULL
                                   AND CAST(c.{{QbFeeCharge.chargeId}} AS text) = i.{{QbFeeInvoice.chargeId}})
                           OR (i.{{QbFeeInvoice.chargeId}} IS NULL
                                   AND c.{{QbFeeCharge.sponsorId}} = i.{{QbFeeInvoice.sponsorId}}
                                   AND c.{{QbFeeCharge.period}} = i.{{QbFeeInvoice.period}}
                                   AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                                   AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} o
                                                   WHERE o.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)))
    UNION ALL
    SELECT g.payment_id, 'NOTE', CAST(c.{{QbFeeCharge.chargeId}} AS text), g.amount, g.paid_at, g.grant_id
    FROM gift g
    JOIN {{QbFeeCreditNote}} n ON g.target_entity = 'QbFeeCreditNote'
        AND CAST(n.{{QbFeeCreditNote.creditNoteId}} AS text) = g.target_id
    JOIN {{QbFeeCharge}} c ON CAST(c.{{QbFeeCharge.chargeId}} AS text) = n.{{QbFeeCreditNote.chargeId}}
), prior AS (
    SELECT l.{{QbOperatorSettlementLine.chargeId}} AS charge_id,
           sum(l.{{QbOperatorSettlementLine.giftGross}}) AS gross,
           sum(l.{{QbOperatorSettlementLine.giftGross}})
               FILTER (WHERE l.{{QbOperatorSettlementLine.noShare}} = false) AS operator_gross,
           sum(l.{{QbOperatorSettlementLine.amount}}) AS amt,
           sum(l.{{QbOperatorSettlementLine.inBase}}) AS in_base,
           sum(l.{{QbOperatorSettlementLine.recoverGross}}) AS recover
    FROM {{QbOperatorSettlementLine}} l
    WHERE l.{{QbOperatorSettlementLine.lineKind}} = 'GIFT'
    GROUP BY l.{{QbOperatorSettlementLine.chargeId}}
)
SELECT r.payment_id AS paymentId, r.charge_id AS chargeId, r.target_kind AS targetKind,
       r.amount AS paymentAmount, r.paid_at AS paidAt, r.grant_id AS grantId,
       gr.{{QbFeeCreditGrant.source}} AS grantSource,
       c.{{QbFeeCharge.sponsorId}} AS sponsorId, c.{{QbFeeCharge.kind}} AS feeKind, c.{{QbFeeCharge.period}} AS period,
       c.{{QbFeeCharge.amount}} AS chargeAmount, c.{{QbFeeCharge.total}} AS chargeTotal,
       c.{{QbFeeCharge.shareExcluded}} AS chargeShareExcluded,
       ln.{{QbOperatorSettlementLine.excluded}} AS lineExcluded,
       ln.{{QbOperatorSettlementLine.shareRate}} AS lineShareRate,
       ln.{{QbOperatorSettlementLine.recoveryRate}} AS lineRecoveryRate,
       coalesce(pr.gross, 0) AS priorGross, coalesce(pr.operator_gross, 0) AS priorOperatorGross,
       coalesce(pr.amt, 0) AS priorAmount, coalesce(pr.in_base, 0) AS priorInBase,
       coalesce(pr.recover, 0) AS priorRecover
FROM resolved r
JOIN {{QbFeeCharge}} c ON CAST(c.{{QbFeeCharge.chargeId}} AS text) = r.charge_id
LEFT JOIN {{QbFeeCreditGrant}} gr ON CAST(gr.{{QbFeeCreditGrant.grantId}} AS text) = r.grant_id
LEFT JOIN {{QbOperatorSettlementLine}} ln ON ln.{{QbOperatorSettlementLine.lineKind}} = 'CHARGE'
    AND ln.{{QbOperatorSettlementLine.chargeId}} = r.charge_id
LEFT JOIN prior pr ON pr.charge_id = r.charge_id
WHERE c.{{QbFeeCharge.operatorId}} = :operatorId
  AND (ln.{{QbOperatorSettlementLine.lineId}} IS NOT NULL OR r.charge_id = ANY(:countedNow))
  AND NOT EXISTS (SELECT 1 FROM {{QbOperatorSettlementLine}} gl
                  WHERE gl.{{QbOperatorSettlementLine.lineKind}} = 'GIFT'
                    AND gl.{{QbOperatorSettlementLine.sourceId}} = CAST(r.payment_id AS text)
                    AND gl.{{QbOperatorSettlementLine.chargeId}} = r.charge_id)
