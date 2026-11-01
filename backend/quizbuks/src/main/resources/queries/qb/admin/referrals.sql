/*---
id: qb.admin.referrals
description: "Every referral (Q70), for support and finance: the publication, the sponsor, the referrer and the referee, the state and the reward, newest first"
entities: [QbReferral, QbPublicationCard]
datasets:
  QbReferral: urn:jabiz:dataset:default:QbReferral
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
params:
  publicationId: { like: QbReferral.publicationId, description: Only this publication's }
  referrerId:    { like: QbReferral.referrerId, description: Only this referrer's }
results:
  referralId:      { from: QbReferral.referralId }
  publicationId:   { from: QbReferral.publicationId }
  publicationName: { from: QbPublicationCard.name }
  publisherKind:   { from: QbReferral.publisherKind }
  publisherId:     { from: QbReferral.publisherId }
  referrerId:      { from: QbReferral.referrerId }
  refereeId:       { from: QbReferral.refereeId }
  status:          { from: QbReferral.status }
  referredAt:      { from: QbReferral.referredAt }
  expiresAt:       { from: QbReferral.expiresAt }
  rewardMethod:    { from: QbReferral.rewardMethod }
  amount:          { from: QbReferral.amount }
  rewardedAt:      { from: QbReferral.rewardedAt }
  fundsShort:      { from: QbReferral.fundsShort }
  voidReason:      { from: QbReferral.voidReason }
list:
  filters: [status, publisherId, referrerId, publicationId, referredAt]
  sorts:   [referredAt, status, amount]
  defaultSort: { field: referredAt, asc: false }
  key: [referralId]
permissions: [qb.admin.referrals.read]
---*/
-- The referrer filter finds a referrer bringing many referees quickly (the risk of D-Q70-19b).
SELECT
    r.{{QbReferral.referralId}}    AS referralId,
    r.{{QbReferral.publicationId}} AS publicationId,
    c.{{QbPublicationCard.name}}   AS publicationName,
    r.{{QbReferral.publisherKind}} AS publisherKind,
    r.{{QbReferral.publisherId}}   AS publisherId,
    r.{{QbReferral.referrerId}}    AS referrerId,
    r.{{QbReferral.refereeId}}     AS refereeId,
    r.{{QbReferral.status}}        AS status,
    r.{{QbReferral.referredAt}}    AS referredAt,
    r.{{QbReferral.expiresAt}}     AS expiresAt,
    r.{{QbReferral.rewardMethod}}  AS rewardMethod,
    r.{{QbReferral.amount}}        AS amount,
    r.{{QbReferral.rewardedAt}}    AS rewardedAt,
    r.{{QbReferral.fundsShort}}    AS fundsShort,
    r.{{QbReferral.voidReason}}    AS voidReason
FROM {{QbReferral}} r
LEFT JOIN {{QbPublicationCard}} c
    ON CAST(c.{{QbPublicationCard.publicationId}} AS text) = r.{{QbReferral.publicationId}}
WHERE (CAST(:publicationId AS text) IS NULL OR r.{{QbReferral.publicationId}} = CAST(:publicationId AS text))
  AND (CAST(:referrerId AS text) IS NULL OR r.{{QbReferral.referrerId}} = CAST(:referrerId AS text))
