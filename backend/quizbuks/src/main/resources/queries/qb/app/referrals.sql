/*---
id: qb.app.referrals
description: "The referrer's own referrals (Q70): to which quiz, until when, what came of it and the reward, newest first; never who the friend is"
entities: [QbReferral, QbPublicationCard]
datasets:
  QbReferral: urn:jabiz:dataset:referrer:QbReferral
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
results:
  referralId:      { from: QbReferral.referralId }
  publicationId:   { from: QbReferral.publicationId }
  publicationName: { from: QbPublicationCard.name }
  status:          { from: QbReferral.status }
  referredAt:      { from: QbReferral.referredAt }
  expiresAt:       { from: QbReferral.expiresAt }
  rewardMethod:    { from: QbReferral.rewardMethod }
  amount:          { from: QbReferral.amount }
  rewardedAt:      { from: QbReferral.rewardedAt }
  endedAt:         { from: QbReferral.endedAt }
list:
  filters: [status, publicationId, referredAt]
  sorts:   [referredAt, status]
  defaultSort: { field: referredAt, asc: false }
  key: [referralId]
permissions: [qb.me]
---*/
-- The view holds the caller's referrals as referrer only (D-Q70-15: the referee is not named, nor why one ended).
SELECT
    r.{{QbReferral.referralId}}    AS referralId,
    r.{{QbReferral.publicationId}} AS publicationId,
    c.{{QbPublicationCard.name}}   AS publicationName,
    r.{{QbReferral.status}}        AS status,
    r.{{QbReferral.referredAt}}    AS referredAt,
    r.{{QbReferral.expiresAt}}     AS expiresAt,
    r.{{QbReferral.rewardMethod}}  AS rewardMethod,
    r.{{QbReferral.amount}}        AS amount,
    r.{{QbReferral.rewardedAt}}    AS rewardedAt,
    r.{{QbReferral.endedAt}}       AS endedAt
FROM {{QbReferral}} r
LEFT JOIN {{QbPublicationCard}} c
    ON CAST(c.{{QbPublicationCard.publicationId}} AS text) = r.{{QbReferral.publicationId}}
