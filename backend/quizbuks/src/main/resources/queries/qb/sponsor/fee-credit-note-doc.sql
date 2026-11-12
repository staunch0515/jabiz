/*---
id: qb.sponsor.fee-credit-note-doc
description: One credit note of the sponsor's own, as its document shows it (F-M7, the document qb.fee_credit_note)
entities: [QbFeeCreditNote, QbSponsor]
datasets:
  QbFeeCreditNote: urn:jabiz:dataset:sponsor:QbFeeCreditNote
  QbSponsor: urn:jabiz:dataset:sponsor:QbSponsor
params:
  creditNoteId: { like: QbFeeCreditNote.creditNoteId, required: true, description: The credit note }
results:
  creditNoteNo:   { from: QbFeeCreditNote.creditNoteNo }
  company:        { from: QbSponsor.company }
  contactName:    { from: QbSponsor.contactName }
  invoiceNo:      { from: QbFeeCreditNote.invoiceNo }
  reason:         { from: QbFeeCreditNote.reason }
  endReason:      { from: QbFeeCreditNote.endReason }
  usedDays:       { from: QbFeeCreditNote.usedDays }
  explanation:    { from: QbFeeCreditNote.note }
  taxRate:        { from: QbFeeCreditNote.taxRate }
  issuedAt:       { from: QbFeeCreditNote.issuedAt }
  refundedCash:   { from: QbFeeCreditNote.refundedCash }
  refundedCredit: { from: QbFeeCreditNote.refundedCredit }
  amount:         { from: QbFeeCreditNote.amount }
  tax:            { from: QbFeeCreditNote.tax }
  total:          { from: QbFeeCreditNote.total }
permissions: [qb.topup]
---*/
-- Through the sponsor's own datasets: another sponsor's note yields no row (CLAUDE.md: a layout a sponsor can read is
-- limited to their own subjects).
SELECT
    n.{{QbFeeCreditNote.creditNoteNo}}   AS creditNoteNo,
    s.{{QbSponsor.company}}              AS company,
    s.{{QbSponsor.contactName}}          AS contactName,
    n.{{QbFeeCreditNote.invoiceNo}}      AS invoiceNo,
    n.{{QbFeeCreditNote.reason}}         AS reason,
    n.{{QbFeeCreditNote.endReason}}      AS endReason,
    n.{{QbFeeCreditNote.usedDays}}       AS usedDays,
    n.{{QbFeeCreditNote.note}}           AS explanation,
    n.{{QbFeeCreditNote.taxRate}}        AS taxRate,
    n.{{QbFeeCreditNote.issuedAt}}       AS issuedAt,
    n.{{QbFeeCreditNote.refundedCash}}   AS refundedCash,
    n.{{QbFeeCreditNote.refundedCredit}} AS refundedCredit,
    n.{{QbFeeCreditNote.amount}}         AS amount,
    n.{{QbFeeCreditNote.tax}}            AS tax,
    n.{{QbFeeCreditNote.total}}          AS total
FROM {{QbFeeCreditNote}} n
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = n.{{QbFeeCreditNote.sponsorId}}
WHERE n.{{QbFeeCreditNote.creditNoteId}} = :creditNoteId
