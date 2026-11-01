/*---
id: qb.sponsor.referrals
description: "The sponsor's referrals (Q70): per quiz, who was referred when, what came of it and the referral reward; never the people's names"
entities: [QbReferral, QbPublicationCard]
datasets:
  QbReferral: urn:jabiz:dataset:sponsor:QbReferral
  QbPublicationCard: urn:jabiz:dataset:sponsor:QbPublicationCard
params:
  publicationId: { like: QbReferral.publicationId, description: Only this publication's }
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
  fundsShort:      { from: QbReferral.fundsShort }
  voidReason:      { from: QbReferral.voidReason }
list:
  filters: [status, publicationId, referredAt]
  sorts:   [referredAt, status, amount]
  defaultSort: { field: referredAt, asc: false }
  key: [referralId]
permissions: [qb.publication.write]
---*/
-- The view holds the sponsor's own referrals (published as a sponsor); the referrer and the referee are not named:
-- the sponsor pays for referrals, not for a list of people (their cash rewards and prizes name the referrer, as
-- every winner is named to the sponsor who hands it over).
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
    r.{{QbReferral.fundsShort}}    AS fundsShort,
    r.{{QbReferral.voidReason}}    AS voidReason
FROM {{QbReferral}} r
LEFT JOIN {{QbPublicationCard}} c
    ON CAST(c.{{QbPublicationCard.publicationId}} AS text) = r.{{QbReferral.publicationId}}
WHERE (CAST(:publicationId AS text) IS NULL OR r.{{QbReferral.publicationId}} = CAST(:publicationId AS text))
