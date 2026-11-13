/*---
id: qb.operator.fee-credit-notes
description: "F-M7: the credit notes on the fees of the caller's operator's sponsors (as charged under it), newest first, read only"
entities: [QbFeeCreditNote, QbFeeCreditNoteUse, QbSponsor, QbOperatorMember]
datasets:
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
  QbFeeCreditNoteUse: urn:jabiz:dataset:default:QbFeeCreditNoteUse
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
results:
  creditNoteId:   { from: QbFeeCreditNote.creditNoteId }
  creditNoteNo:   { from: QbFeeCreditNote.creditNoteNo }
  sponsorId:      { from: QbFeeCreditNote.sponsorId }
  company:        { from: QbSponsor.company }
  operatorId:     { from: QbFeeCreditNote.operatorId }
  chargeId:       { from: QbFeeCreditNote.chargeId }
  invoiceNo:      { from: QbFeeCreditNote.invoiceNo }
  publicationId:  { from: QbFeeCreditNote.publicationId }
  billingMode:    { from: QbFeeCreditNote.billingMode }
  reason:         { from: QbFeeCreditNote.reason }
  endReason:      { from: QbFeeCreditNote.endReason }
  usedDays:       { from: QbFeeCreditNote.usedDays }
  note:           { from: QbFeeCreditNote.note }
  amount:         { from: QbFeeCreditNote.amount }
  tax:            { from: QbFeeCreditNote.tax }
  total:          { from: QbFeeCreditNote.total }
  appliedOwn:     { from: QbFeeCreditNote.appliedOwn }
  refundedCash:   { from: QbFeeCreditNote.refundedCash }
  refundedCredit: { from: QbFeeCreditNote.refundedCredit }
  voidedCredit:   { from: QbFeeCreditNote.voidedCredit }
  used:           { kind: { type: monetary, currency: JPY, scale: 0 } }
  creditLeft:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  issuedAt:       { from: QbFeeCreditNote.issuedAt }
  issuedBy:       { from: QbFeeCreditNote.issuedBy }
list:
  filters: [sponsorId, operatorId, chargeId, invoiceNo, reason, issuedAt]
  sorts:   [issuedAt, total]
  defaultSort: { field: issuedAt, asc: false }
  key: [creditNoteId]
permissions: [qb.operator.use]
---*/
-- Only the notes on fees charged under the caller's operator (the membership dataset is the caller's own; the note
-- keeps the operator its fee was charged under, as the settlement deducts by it).
SELECT
    n.{{QbFeeCreditNote.creditNoteId}}   AS creditNoteId,
    n.{{QbFeeCreditNote.creditNoteNo}}   AS creditNoteNo,
    n.{{QbFeeCreditNote.sponsorId}}      AS sponsorId,
    s.{{QbSponsor.company}}              AS company,
    n.{{QbFeeCreditNote.operatorId}}     AS operatorId,
    n.{{QbFeeCreditNote.chargeId}}       AS chargeId,
    n.{{QbFeeCreditNote.invoiceNo}}      AS invoiceNo,
    n.{{QbFeeCreditNote.publicationId}}  AS publicationId,
    n.{{QbFeeCreditNote.billingMode}}    AS billingMode,
    n.{{QbFeeCreditNote.reason}}         AS reason,
    n.{{QbFeeCreditNote.endReason}}      AS endReason,
    n.{{QbFeeCreditNote.usedDays}}       AS usedDays,
    n.{{QbFeeCreditNote.note}}           AS note,
    n.{{QbFeeCreditNote.amount}}         AS amount,
    n.{{QbFeeCreditNote.tax}}            AS tax,
    n.{{QbFeeCreditNote.total}}          AS total,
    n.{{QbFeeCreditNote.appliedOwn}}     AS appliedOwn,
    n.{{QbFeeCreditNote.refundedCash}}   AS refundedCash,
    n.{{QbFeeCreditNote.refundedCredit}} AS refundedCredit,
    n.{{QbFeeCreditNote.voidedCredit}}   AS voidedCredit,
    coalesce(u.r_used, 0)                AS used,
    n.{{QbFeeCreditNote.total}} - n.{{QbFeeCreditNote.refundedCash}} - n.{{QbFeeCreditNote.refundedCredit}}
        - coalesce(u.r_used, 0)          AS creditLeft,
    n.{{QbFeeCreditNote.issuedAt}}       AS issuedAt,
    -- Who in headquarters issued it is not the operator's to read.
    CAST(NULL AS varchar(128))           AS issuedBy
FROM {{QbFeeCreditNote}} n
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = n.{{QbFeeCreditNote.sponsorId}}
LEFT JOIN (SELECT x.{{QbFeeCreditNoteUse.creditNoteId}} AS r_note, sum(x.{{QbFeeCreditNoteUse.amount}}) AS r_used
           FROM {{QbFeeCreditNoteUse}} x GROUP BY x.{{QbFeeCreditNoteUse.creditNoteId}}) u
       ON u.r_note = CAST(n.{{QbFeeCreditNote.creditNoteId}} AS text)
JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
  AND CAST(me.{{QbOperatorMember.operatorId}} AS text) = n.{{QbFeeCreditNote.operatorId}}
