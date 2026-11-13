/*---
id: qb.system.credit-notes-left
description: "A sponsor's credit notes with credit left (F-M7 §10.3-4, H1 of the review): only these are read, however many notes there are"
entities: [QbFeeCreditNote, QbFeeCreditNoteUse]
datasets:
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
  QbFeeCreditNoteUse: urn:jabiz:dataset:default:QbFeeCreditNoteUse
params:
  sponsorId: { like: QbFeeCreditNote.sponsorId, required: true, description: The sponsor }
results:
  creditNoteId: { from: QbFeeCreditNote.creditNoteId }
  creditNoteNo: { from: QbFeeCreditNote.creditNoteNo }
  issuedAt:     { from: QbFeeCreditNote.issuedAt }
  creditLeft:   { from: QbFeeCreditNote.total }
permissions: [qb.fee.system]
---*/
-- What is left of a note's credit: its total less what it gave back and what its credit paid (its own invoice
-- included), as CreditNotes.left reads it. The order (the earliest issued first) is the caller's.
SELECT CAST(n.{{QbFeeCreditNote.creditNoteId}} AS text) AS creditNoteId,
       n.{{QbFeeCreditNote.creditNoteNo}} AS creditNoteNo,
       n.{{QbFeeCreditNote.issuedAt}} AS issuedAt,
       n.{{QbFeeCreditNote.total}} - n.{{QbFeeCreditNote.refundedCash}} - n.{{QbFeeCreditNote.refundedCredit}}
           - coalesce(u.used, 0) AS creditLeft
FROM {{QbFeeCreditNote}} n
LEFT JOIN (SELECT x.{{QbFeeCreditNoteUse.creditNoteId}} AS note_id, sum(x.{{QbFeeCreditNoteUse.amount}}) AS used
           FROM {{QbFeeCreditNoteUse}} x
           WHERE x.{{QbFeeCreditNoteUse.sponsorId}} = :sponsorId
           GROUP BY x.{{QbFeeCreditNoteUse.creditNoteId}}) u
       ON u.note_id = CAST(n.{{QbFeeCreditNote.creditNoteId}} AS text)
WHERE n.{{QbFeeCreditNote.sponsorId}} = :sponsorId
  AND n.{{QbFeeCreditNote.total}} - n.{{QbFeeCreditNote.refundedCash}} - n.{{QbFeeCreditNote.refundedCredit}}
          - coalesce(u.used, 0) > 0
