/*---
id: qb.sponsor.publication-prepayments
description: "What a Sponsor's publication set aside of their account for its fees (F-M5): each fee's prepayment, what it still holds, and when it was charged from or given back"
entities: [QbPrepayment]
datasets:
  QbPrepayment: urn:jabiz:dataset:sponsor:QbPrepayment
params:
  publicationId: { like: QbPrepayment.publicationId, required: true, description: The publication }
results:
  prepaymentId:  { from: QbPrepayment.prepaymentId }
  purpose:       { from: QbPrepayment.purpose }
  mode:          { from: QbPrepayment.mode }
  amount:        { from: QbPrepayment.amount }
  remaining:     { from: QbPrepayment.remaining }
  status:        { from: QbPrepayment.status }
  frozenAt:      { from: QbPrepayment.frozenAt }
  chargedAt:     { from: QbPrepayment.chargedAt }
  releasedAt:    { from: QbPrepayment.releasedAt }
  releaseReason: { from: QbPrepayment.releaseReason }
list:
  sorts: [frozenAt]
  defaultSort: { field: frozenAt, asc: false }
  key: [prepaymentId]
permissions: [qb.sponsor.finance.read]
---*/
-- The dataset is the caller's own: another Sponsor's publication has no rows. Every submission's, newest first (a
-- publication withdrawn and submitted again has one given back and one held).
SELECT p.{{QbPrepayment.prepaymentId}} AS prepaymentId, p.{{QbPrepayment.purpose}} AS purpose,
       p.{{QbPrepayment.mode}} AS mode, p.{{QbPrepayment.amount}} AS amount,
       p.{{QbPrepayment.remaining}} AS remaining, p.{{QbPrepayment.status}} AS status,
       p.{{QbPrepayment.frozenAt}} AS frozenAt, p.{{QbPrepayment.chargedAt}} AS chargedAt,
       p.{{QbPrepayment.releasedAt}} AS releasedAt, p.{{QbPrepayment.releaseReason}} AS releaseReason
FROM {{QbPrepayment}} p
WHERE p.{{QbPrepayment.publicationId}} = :publicationId
