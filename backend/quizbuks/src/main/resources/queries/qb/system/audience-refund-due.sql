/*---
id: qb.system.audience-refund-due
description: "The audience fees whose refund of unused days is not decided yet, of publications that ended by the given time (the items of the daily job qb.audience-refund, F-M7)"
entities: [QbPrepayment, QbPublicationCard]
datasets:
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
params:
  at: { like: QbPrepayment.frozenAt, required: true, description: "The run's time" }
results:
  prepaymentId:  { kind: { type: text, maxLength: 36 } }
  publicationId: { kind: { type: text, maxLength: 36 } }
  sponsorId:     { kind: { type: text, maxLength: 128 } }
  endedAt:       { from: QbPublicationCard.endedAt }
list:
  sorts: [prepaymentId]
  defaultSort: { field: prepaymentId, asc: true }
  key: [prepaymentId]
permissions: [qb.fee.system]
---*/
-- Once charged, an audience fee is decided once (refund_decided_at, F-M7 §10.3-6); a decided one is never taken
-- again, whatever the switch is later. Through the partial index on what is not decided (V119). The item reads the
-- prepayment, the publication and the fee again under the sponsor's wallet lock.
SELECT CAST(p.{{QbPrepayment.prepaymentId}} AS text) AS prepaymentId,
       p.{{QbPrepayment.publicationId}} AS publicationId, p.{{QbPrepayment.sponsorId}} AS sponsorId,
       c.{{QbPublicationCard.endedAt}} AS endedAt
FROM {{QbPrepayment}} p
JOIN {{QbPublicationCard}} c ON CAST(c.{{QbPublicationCard.publicationId}} AS text) = p.{{QbPrepayment.publicationId}}
WHERE p.{{QbPrepayment.purpose}} = 'AUDIENCE'
  AND p.{{QbPrepayment.chargedAt}} IS NOT NULL
  AND p.{{QbPrepayment.refundDecidedAt}} IS NULL
  AND c.{{QbPublicationCard.status}} = 'ENDED'
  AND c.{{QbPublicationCard.endedAt}} <= :at
