/*---
id: qb.sponsor.fee-credit-note-lines
description: What one credit note of the sponsor's own paid of their invoices (F-M7), the lines of its document
entities: [QbFeeCreditNote, QbFeeCreditNoteUse, QbFeeInvoice]
datasets:
  QbFeeCreditNote: urn:jabiz:dataset:sponsor:QbFeeCreditNote
  QbFeeCreditNoteUse: urn:jabiz:dataset:sponsor:QbFeeCreditNoteUse
  QbFeeInvoice: urn:jabiz:dataset:sponsor:QbFeeInvoice
params:
  creditNoteId: { like: QbFeeCreditNote.creditNoteId, required: true, description: The credit note }
results:
  useId:     { from: QbFeeCreditNoteUse.useId }
  invoiceNo: { from: QbFeeInvoice.invoiceNo }
  usedAt:    { from: QbFeeCreditNoteUse.usedAt }
  amount:    { from: QbFeeCreditNoteUse.amount }
list:
  sorts: [usedAt]
  defaultSort: { field: usedAt, asc: true }
  key: [useId]
permissions: [qb.topup]
---*/
-- The invoices its credit paid, in the order it paid them, as at the time the document is issued: the document is
-- issued once and kept exactly, so a later invoice that takes what was left is on its own invoice ("paid by credit
-- notes"), not on this document.
SELECT
    u.{{QbFeeCreditNoteUse.useId}}  AS useId,
    i.{{QbFeeInvoice.invoiceNo}}    AS invoiceNo,
    u.{{QbFeeCreditNoteUse.usedAt}} AS usedAt,
    u.{{QbFeeCreditNoteUse.amount}} AS amount
FROM {{QbFeeCreditNote}} n
JOIN {{QbFeeCreditNoteUse}} u ON u.{{QbFeeCreditNoteUse.creditNoteId}} = CAST(n.{{QbFeeCreditNote.creditNoteId}} AS text)
JOIN {{QbFeeInvoice}} i ON CAST(i.{{QbFeeInvoice.invoiceId}} AS text) = u.{{QbFeeCreditNoteUse.invoiceId}}
WHERE n.{{QbFeeCreditNote.creditNoteId}} = :creditNoteId
