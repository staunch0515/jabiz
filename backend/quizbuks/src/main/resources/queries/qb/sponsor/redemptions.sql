/*---
id: qb.sponsor.redemptions
description: "The prizes takers collect at the sponsor's place (Q22b), newest first: claims to confirm, collected and open ones; searched by code or taker"
entities: [QbRedemption, QbPublicationCard, QbProfile]
datasets:
  QbRedemption: urn:jabiz:dataset:sponsor:QbRedemption
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbProfile: urn:jabiz:dataset:default:QbProfile
params:
  q:     { kind: { type: text, maxLength: 100 }, description: "Only prizes whose code or the taker's name contains this text" }
  since: { like: QbRedemption.dueAt, description: "Only prizes claimed or collected at or after this time (the redeem page's recent list)" }
results:
  redemptionId:    { from: QbRedemption.redemptionId }
  code:            { from: QbRedemption.code }
  publicationId:   { from: QbRedemption.publicationId }
  publicationName: { from: QbPublicationCard.name }
  takerName:       { from: QbProfile.displayName }
  amount:          { from: QbRedemption.amount }
  description:     { from: QbRedemption.description }
  status:          { from: QbRedemption.status }
  dueAt:           { from: QbRedemption.dueAt }
  expiresAt:       { from: QbRedemption.expiresAt }
  claimKind:       { from: QbRedemption.claimKind }
  claimedAt:       { from: QbRedemption.claimedAt }
  confirmBy:       { from: QbRedemption.confirmBy }
  redeemedAt:      { from: QbRedemption.redeemedAt }
list:
  filters: [status, publicationId, dueAt, claimedAt, redeemedAt]
  sorts:   [dueAt, claimedAt, redeemedAt, amount, status]
  defaultSort: { field: dueAt, asc: false }
  key: [redemptionId]
permissions: [qb.publication.write]
---*/
-- The taker's name as their profile gives it (none without a profile): what the sponsor sees at the counter next to
-- the code. The text is found as written: strpos takes it literally, so % _ and \ match themselves.
SELECT
    r.{{QbRedemption.redemptionId}}   AS redemptionId,
    r.{{QbRedemption.code}}           AS code,
    r.{{QbRedemption.publicationId}}  AS publicationId,
    c.{{QbPublicationCard.name}}      AS publicationName,
    p.{{QbProfile.displayName}}       AS takerName,
    r.{{QbRedemption.amount}}         AS amount,
    r.{{QbRedemption.description}}    AS description,
    r.{{QbRedemption.status}}         AS status,
    r.{{QbRedemption.dueAt}}          AS dueAt,
    r.{{QbRedemption.expiresAt}}      AS expiresAt,
    r.{{QbRedemption.claimKind}}      AS claimKind,
    r.{{QbRedemption.claimedAt}}      AS claimedAt,
    r.{{QbRedemption.confirmBy}}      AS confirmBy,
    r.{{QbRedemption.redeemedAt}}     AS redeemedAt
FROM {{QbRedemption}} r
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = r.{{QbRedemption.publicationId}}
LEFT JOIN {{QbProfile}} p ON p.{{QbProfile.userId}} = r.{{QbRedemption.userId}}
WHERE (CAST(:q AS text) IS NULL
       OR strpos(lower(r.{{QbRedemption.code}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(p.{{QbProfile.displayName}}, '')), lower(CAST(:q AS text))) > 0)
  AND (CAST(:since AS timestamptz) IS NULL
       OR r.{{QbRedemption.claimedAt}} >= :since OR r.{{QbRedemption.redeemedAt}} >= :since)
