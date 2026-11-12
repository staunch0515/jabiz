/*---
id: qb.sponsor.fee-credit-notes
description: "The sponsor's own credit notes (F-M7), newest first: what each credited, why, what it gave back, what is left of its credit and its document"
entities: [QbFeeCreditNote, QbFeeCreditNoteUse, QbFeeCreditNoteDocument]
datasets:
  QbFeeCreditNote: urn:jabiz:dataset:sponsor:QbFeeCreditNote
  QbFeeCreditNoteUse: urn:jabiz:dataset:sponsor:QbFeeCreditNoteUse
  QbFeeCreditNoteDocument: urn:jabiz:dataset:sponsor:QbFeeCreditNoteDocument
results:
  creditNoteId:   { from: QbFeeCreditNote.creditNoteId }
  creditNoteNo:   { from: QbFeeCreditNote.creditNoteNo }
  invoiceNo:      { from: QbFeeCreditNote.invoiceNo }
  publicationId:  { from: QbFeeCreditNote.publicationId }
  reason:         { from: QbFeeCreditNote.reason }
  endReason:      { from: QbFeeCreditNote.endReason }
  usedDays:       { from: QbFeeCreditNote.usedDays }
  amount:         { from: QbFeeCreditNote.amount }
  tax:            { from: QbFeeCreditNote.tax }
  total:          { from: QbFeeCreditNote.total }
  refundedCash:   { from: QbFeeCreditNote.refundedCash }
  refundedCredit: { from: QbFeeCreditNote.refundedCredit }
  used:           { kind: { type: monetary, currency: JPY, scale: 0 } }
  creditLeft:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  issuedAt:       { from: QbFeeCreditNote.issuedAt }
  documentNo:     { from: QbFeeCreditNoteDocument.documentNo }
list:
  filters: [publicationId, reason, issuedAt]
  sorts:   [issuedAt, total]
  defaultSort: { field: issuedAt, asc: false }
  key: [creditNoteId]
permissions: [qb.sponsor.finance.read]
---*/
-- Every dataset is the caller's own. Used: what its credit paid of invoices (the fee's own included); left: its total
-- less what it gave back and used, which the next invoices take first.
SELECT
    n.{{QbFeeCreditNote.creditNoteId}}   AS creditNoteId,
    n.{{QbFeeCreditNote.creditNoteNo}}   AS creditNoteNo,
    n.{{QbFeeCreditNote.invoiceNo}}      AS invoiceNo,
    n.{{QbFeeCreditNote.publicationId}}  AS publicationId,
    n.{{QbFeeCreditNote.reason}}         AS reason,
    n.{{QbFeeCreditNote.endReason}}      AS endReason,
    n.{{QbFeeCreditNote.usedDays}}       AS usedDays,
    n.{{QbFeeCreditNote.amount}}         AS amount,
    n.{{QbFeeCreditNote.tax}}            AS tax,
    n.{{QbFeeCreditNote.total}}          AS total,
    n.{{QbFeeCreditNote.refundedCash}}   AS refundedCash,
    n.{{QbFeeCreditNote.refundedCredit}} AS refundedCredit,
    coalesce(u.r_used, 0)                AS used,
    n.{{QbFeeCreditNote.total}} - n.{{QbFeeCreditNote.refundedCash}} - n.{{QbFeeCreditNote.refundedCredit}}
        - coalesce(u.r_used, 0)          AS creditLeft,
    n.{{QbFeeCreditNote.issuedAt}}       AS issuedAt,
    d.{{QbFeeCreditNoteDocument.documentNo}} AS documentNo
FROM {{QbFeeCreditNote}} n
LEFT JOIN (SELECT x.{{QbFeeCreditNoteUse.creditNoteId}} AS r_note, sum(x.{{QbFeeCreditNoteUse.amount}}) AS r_used
           FROM {{QbFeeCreditNoteUse}} x GROUP BY x.{{QbFeeCreditNoteUse.creditNoteId}}) u
       ON u.r_note = CAST(n.{{QbFeeCreditNote.creditNoteId}} AS text)
LEFT JOIN {{QbFeeCreditNoteDocument}} d
       ON d.{{QbFeeCreditNoteDocument.creditNoteId}} = CAST(n.{{QbFeeCreditNote.creditNoteId}} AS text)
