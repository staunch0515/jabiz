/*---
id: qb.system.operator-credit-notes
description: "The credit notes issued in a month on an operator's fees (F-M7 C3, before tax); the operator gives back its share of them"
entities: [QbFeeCreditNote]
datasets:
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
params:
  operatorId: { like: QbFeeCreditNote.operatorId, required: true, description: The operator }
  from:       { like: QbFeeCreditNote.issuedAt, required: true, description: "The month's first instant" }
  to:         { like: QbFeeCreditNote.issuedAt, required: true, description: "The next month's first instant" }
results:
  creditNotes: { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.operator.settlement.system]
---*/
-- Always one row. The credit notes issued in the month on the operator's fees, before tax, whatever month the fee was
-- charged (the settlement takes the operator's share of them back, as the prospecting cost).
SELECT coalesce(sum(n.{{QbFeeCreditNote.amount}}), 0) AS creditNotes
FROM {{QbFeeCreditNote}} n
WHERE n.{{QbFeeCreditNote.operatorId}} = :operatorId
  AND n.{{QbFeeCreditNote.issuedAt}} >= :from
  AND n.{{QbFeeCreditNote.issuedAt}} < :to
