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
  countedNow:  { kind: { type: text, maxLength: 36 }, list: true, required: true, description: "The ids of the fees that wait to be counted in this settlement (none so far have a CHARGE line)" }
  maxPrior:    { kind: { type: numeric, precision: 9, scale: 0 }, required: true, description: "At most this many payments on fees counted before, the oldest first (the cap on a settlement's lines, plus one)" }
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
  lineRecoverBase:    { from: QbOperatorSettlementLine.recoverBase }
  chargeOperatorId:   { from: QbFeeCharge.operatorId }
  counted:            { kind: { type: bool } }
  done:               { kind: { type: bool } }
  priorNoShare:       { kind: { type: bool } }
  priorGross:         { kind: { type: monetary, currency: JPY, scale: 0 } }
  priorOperatorGross: { kind: { type: monetary, currency: JPY, scale: 0 } }
  priorAmount:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  priorInBase:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  priorRecover:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  forNewFee:          { kind: { type: bool } }
permissions: [qb.operator.settlement.system]
---*/
-- A FEE_CREDIT part aims at a fee, an invoice or a credit note (CreditNotes.recordBack writes the credit given back as a
-- negative part aimed at the note). Each is resolved to the fee(s) it paid: a charge directly, an invoice's charges
-- (each its own row, so a month's invoice of two operators' fees splits each to its own operator), a note by its fee.
-- Every fee of an invoice comes back (with its operator, whether it is counted and whether its part is written already):
-- the part is cut over ALL of them by their totals, and only then the caller keeps this operator's counted ones that
-- have no GIFT line yet; a payment comes back only if at least one such fee is on it. The CHARGE line's rates and
-- recover base, the fee's prior gift rows and the treatment its grant was first given ride along.
--
-- Bounded by this operator (F-M12 b, review H2), not by the platform: the payments are found from THIS operator's fees
-- (since the switch point): directly by the fee (payment.target_id), through the fee's invoice (its own, or the month's
-- of the sponsor), or through a credit note on it; only then are an invoice's other fees expanded for the cut. Every
-- join to a fee is on the uuid (the cast is on the payment's side, so the fee's unique index is the inner side) and the
-- payment side is read through its target index. Two kinds of payment come back: those on the fees that wait (countedNow:
-- the cap decides in the caller which are counted now; each brings its fee's credit-used lines) and the new ones on fees
-- counted before, the oldest maxPrior of them (the rest wait for the next settlement; none is lost, a payment with a
-- line written for it is `done`).
WITH mine AS (
    SELECT c.{{QbFeeCharge.chargeId}} AS charge_uuid, c.{{QbFeeCharge.sponsorId}} AS sponsor_id,
           c.{{QbFeeCharge.period}} AS period
    FROM {{QbFeeCharge}} c
    WHERE c.{{QbFeeCharge.operatorId}} = :operatorId
      AND c.{{QbFeeCharge.chargedAt}} >= :coveredFrom
), target AS (
    SELECT 'QbFeeCharge' AS entity, CAST(m.charge_uuid AS text) AS id FROM mine m
    UNION
    SELECT 'QbFeeInvoice', CAST(i.{{QbFeeInvoice.invoiceId}} AS text)
    FROM mine m
    JOIN {{QbFeeInvoice}} i ON i.{{QbFeeInvoice.chargeId}} = CAST(m.charge_uuid AS text)
    UNION
    SELECT 'QbFeeInvoice', CAST(i.{{QbFeeInvoice.invoiceId}} AS text)
    FROM mine m
    JOIN {{QbFeeInvoice}} i ON i.{{QbFeeInvoice.chargeId}} IS NULL AND i.{{QbFeeInvoice.sponsorId}} = m.sponsor_id
                           AND i.{{QbFeeInvoice.period}} = m.period
    UNION
    SELECT 'QbFeeCreditNote', CAST(n.{{QbFeeCreditNote.creditNoteId}} AS text)
    FROM mine m
    JOIN {{QbFeeCreditNote}} n ON n.{{QbFeeCreditNote.chargeId}} = CAST(m.charge_uuid AS text)
), gift AS (
    SELECT p.{{QbFeePayment.paymentId}} AS payment_id, p.{{QbFeePayment.targetEntity}} AS target_entity,
           p.{{QbFeePayment.targetId}} AS target_id, p.{{QbFeePayment.amount}} AS amount,
           p.{{QbFeePayment.paidAt}} AS paid_at, p.{{QbFeePayment.grantId}} AS grant_id
    FROM target t
    JOIN {{QbFeePayment}} p ON p.{{QbFeePayment.targetEntity}} = t.entity AND p.{{QbFeePayment.targetId}} = t.id
    WHERE p.{{QbFeePayment.subAccount}} = 'FEE_CREDIT'
      AND p.{{QbFeePayment.paidAt}} >= :coveredFrom
), resolved AS (
    SELECT g.payment_id, 'CHARGE' AS target_kind, c.{{QbFeeCharge.chargeId}} AS charge_uuid, g.amount, g.paid_at, g.grant_id
    FROM gift g
    JOIN {{QbFeeCharge}} c ON c.{{QbFeeCharge.chargeId}} = CAST(g.target_id AS uuid)
    WHERE g.target_entity = 'QbFeeCharge'
    UNION ALL
    SELECT g.payment_id, 'INVOICE', c.{{QbFeeCharge.chargeId}}, g.amount, g.paid_at, g.grant_id
    FROM gift g
    JOIN {{QbFeeInvoice}} i ON i.{{QbFeeInvoice.invoiceId}} = g.target_id
    JOIN {{QbFeeCharge}} c ON c.{{QbFeeCharge.chargeId}} = CAST(i.{{QbFeeInvoice.chargeId}} AS uuid)
    WHERE g.target_entity = 'QbFeeInvoice' AND i.{{QbFeeInvoice.chargeId}} IS NOT NULL
    UNION ALL
    SELECT g.payment_id, 'INVOICE', c.{{QbFeeCharge.chargeId}}, g.amount, g.paid_at, g.grant_id
    FROM gift g
    JOIN {{QbFeeInvoice}} i ON i.{{QbFeeInvoice.invoiceId}} = g.target_id
    JOIN {{QbFeeCharge}} c ON c.{{QbFeeCharge.sponsorId}} = i.{{QbFeeInvoice.sponsorId}}
                           AND c.{{QbFeeCharge.period}} = i.{{QbFeeInvoice.period}}
                           AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                           AND c.{{QbFeeCharge.chargedAt}} <= i.{{QbFeeInvoice.issuedAt}}
    WHERE g.target_entity = 'QbFeeInvoice' AND i.{{QbFeeInvoice.chargeId}} IS NULL
      AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} o
                      WHERE o.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))
    UNION ALL
    SELECT g.payment_id, 'NOTE', c.{{QbFeeCharge.chargeId}}, g.amount, g.paid_at, g.grant_id
    FROM gift g
    JOIN {{QbFeeCreditNote}} n ON n.{{QbFeeCreditNote.creditNoteId}} = CAST(g.target_id AS uuid)
    JOIN {{QbFeeCharge}} c ON c.{{QbFeeCharge.chargeId}} = CAST(n.{{QbFeeCreditNote.chargeId}} AS uuid)
    WHERE g.target_entity = 'QbFeeCreditNote'
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
      AND l.{{QbOperatorSettlementLine.operatorId}} = :operatorId
    GROUP BY l.{{QbOperatorSettlementLine.chargeId}}
), cut AS (
    SELECT x.*,
           dense_rank() OVER (PARTITION BY x.is_new ORDER BY x.paidAt, CAST(x.paymentId AS text) COLLATE "C") AS rank_in_kind
    FROM (
        SELECT r.payment_id AS paymentId, CAST(r.charge_uuid AS text) AS chargeId, r.target_kind AS targetKind,
               r.amount AS paymentAmount, r.paid_at AS paidAt, r.grant_id AS grantId,
               gr.{{QbFeeCreditGrant.source}} AS grantSource,
               c.{{QbFeeCharge.sponsorId}} AS sponsorId, c.{{QbFeeCharge.kind}} AS feeKind,
               c.{{QbFeeCharge.period}} AS period,
               c.{{QbFeeCharge.amount}} AS chargeAmount, c.{{QbFeeCharge.total}} AS chargeTotal,
               c.{{QbFeeCharge.shareExcluded}} AS chargeShareExcluded,
               ln.{{QbOperatorSettlementLine.excluded}} AS lineExcluded,
               ln.{{QbOperatorSettlementLine.shareRate}} AS lineShareRate,
               ln.{{QbOperatorSettlementLine.recoveryRate}} AS lineRecoveryRate,
               ln.{{QbOperatorSettlementLine.recoverBase}} AS lineRecoverBase,
               c.{{QbFeeCharge.operatorId}} AS chargeOperatorId,
               (ln.{{QbOperatorSettlementLine.lineId}} IS NOT NULL
                OR CAST(r.charge_uuid AS text) = ANY(:countedNow)) AS counted,
               EXISTS (SELECT 1 FROM {{QbOperatorSettlementLine}} gl
                       WHERE gl.{{QbOperatorSettlementLine.lineKind}} = 'GIFT'
                         AND gl.{{QbOperatorSettlementLine.sourceId}} = CAST(r.payment_id AS text)
                         AND gl.{{QbOperatorSettlementLine.chargeId}} = CAST(r.charge_uuid AS text)) AS done,
               (SELECT gn.{{QbOperatorSettlementLine.noShare}} FROM {{QbOperatorSettlementLine}} gn
                WHERE gn.{{QbOperatorSettlementLine.lineKind}} = 'GIFT'
                  AND gn.{{QbOperatorSettlementLine.operatorId}} = :operatorId
                  AND gn.{{QbOperatorSettlementLine.chargeId}} = CAST(r.charge_uuid AS text)
                  AND gn.{{QbOperatorSettlementLine.grantId}} = r.grant_id
                ORDER BY gn.{{QbOperatorSettlementLine.occurredAt}}, gn.{{QbOperatorSettlementLine.lineId}}
                LIMIT 1) AS priorNoShare,
               coalesce(pr.gross, 0) AS priorGross, coalesce(pr.operator_gross, 0) AS priorOperatorGross,
               coalesce(pr.amt, 0) AS priorAmount, coalesce(pr.in_base, 0) AS priorInBase,
               coalesce(pr.recover, 0) AS priorRecover,
               bool_or(c.{{QbFeeCharge.operatorId}} = :operatorId
                       AND (ln.{{QbOperatorSettlementLine.lineId}} IS NOT NULL
                            OR CAST(r.charge_uuid AS text) = ANY(:countedNow))
                       AND NOT EXISTS (SELECT 1 FROM {{QbOperatorSettlementLine}} gd
                                       WHERE gd.{{QbOperatorSettlementLine.lineKind}} = 'GIFT'
                                         AND gd.{{QbOperatorSettlementLine.sourceId}} = CAST(r.payment_id AS text)
                                         AND gd.{{QbOperatorSettlementLine.chargeId}} = CAST(r.charge_uuid AS text)))
                   OVER (PARTITION BY r.payment_id) AS relevant,
               bool_or(c.{{QbFeeCharge.operatorId}} = :operatorId AND CAST(r.charge_uuid AS text) = ANY(:countedNow))
                   OVER (PARTITION BY r.payment_id) AS is_new
        FROM resolved r
        JOIN {{QbFeeCharge}} c ON c.{{QbFeeCharge.chargeId}} = r.charge_uuid
        LEFT JOIN {{QbFeeCreditGrant}} gr ON CAST(gr.{{QbFeeCreditGrant.grantId}} AS text) = r.grant_id
        LEFT JOIN {{QbOperatorSettlementLine}} ln ON ln.{{QbOperatorSettlementLine.lineKind}} = 'CHARGE'
            AND ln.{{QbOperatorSettlementLine.chargeId}} = CAST(r.charge_uuid AS text)
        LEFT JOIN prior pr ON pr.charge_id = CAST(r.charge_uuid AS text)
    ) x
    WHERE x.relevant
)
SELECT q.paymentId, q.chargeId, q.targetKind, q.paymentAmount, q.paidAt, q.grantId, q.grantSource, q.sponsorId,
       q.feeKind, q.period, q.chargeAmount, q.chargeTotal, q.chargeShareExcluded, q.lineExcluded, q.lineShareRate,
       q.lineRecoveryRate, q.lineRecoverBase, q.chargeOperatorId, q.counted, q.done, q.priorNoShare,
       q.priorGross, q.priorOperatorGross, q.priorAmount, q.priorInBase, q.priorRecover, q.is_new AS forNewFee
FROM cut q
WHERE q.is_new OR q.rank_in_kind <= :maxPrior
