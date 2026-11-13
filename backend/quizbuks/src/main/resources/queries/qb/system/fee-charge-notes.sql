/*---
id: qb.system.fee-charge-notes
description: "The credit notes so far on one fee (F-M7 §10.3-12, H1 of the review): its limits, and the notes whose parts given back it reads"
entities: [QbFeeCreditNote]
datasets:
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
params:
  chargeId: { like: QbFeeCreditNote.chargeId, required: true, description: The fee }
results:
  creditNoteId: { from: QbFeeCreditNote.creditNoteId }
  amount:       { from: QbFeeCreditNote.amount }
  tax:          { from: QbFeeCreditNote.tax }
permissions: [qb.fee.system]
---*/
SELECT CAST(n.{{QbFeeCreditNote.creditNoteId}} AS text) AS creditNoteId,
       n.{{QbFeeCreditNote.amount}} AS amount,
       n.{{QbFeeCreditNote.tax}} AS tax
FROM {{QbFeeCreditNote}} n
WHERE n.{{QbFeeCreditNote.chargeId}} = :chargeId
