/*---
id: qb.admin.redemptions
description: "Every prize to collect at a sponsor's place (Q22b) with its sponsor and taker, newest first"
entities: [QbRedemption, QbPublicationCard, QbSponsor, QbProfile]
datasets:
  QbRedemption: urn:jabiz:dataset:default:QbRedemption
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbProfile: urn:jabiz:dataset:default:QbProfile
params:
  q: { kind: { type: text, maxLength: 100 }, description: "Only prizes whose code, the sponsor's company or the taker's name contains this text" }
results:
  redemptionId:    { from: QbRedemption.redemptionId }
  code:            { from: QbRedemption.code }
  attemptId:       { from: QbRedemption.attemptId }
  sourceKind:      { from: QbRedemption.sourceKind }
  publicationId:   { from: QbRedemption.publicationId }
  publicationName: { from: QbPublicationCard.name }
  publisherKind:   { from: QbRedemption.publisherKind }
  publisherId:     { from: QbRedemption.publisherId }
  sponsorCompany:  { from: QbSponsor.company }
  userId:          { from: QbRedemption.userId }
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
  redeemedBy:      { from: QbRedemption.redeemedBy }
  voidedBy:        { from: QbRedemption.voidedBy }
  voidNote:        { from: QbRedemption.voidNote }
list:
  filters: [status, publisherKind, publisherId, userId, publicationId, dueAt, expiresAt, amount]
  sorts:   [dueAt, expiresAt, redeemedAt, amount, status]
  defaultSort: { field: dueAt, asc: false }
  key: [redemptionId]
permissions: [qb.admin.publication.read]
---*/
-- The text is found as written: strpos takes it literally, so % _ and \ match themselves.
SELECT
    r.{{QbRedemption.redemptionId}}   AS redemptionId,
    r.{{QbRedemption.code}}           AS code,
    r.{{QbRedemption.attemptId}}      AS attemptId,
    r.{{QbRedemption.sourceKind}}      AS sourceKind,
    r.{{QbRedemption.publicationId}}  AS publicationId,
    c.{{QbPublicationCard.name}}      AS publicationName,
    r.{{QbRedemption.publisherKind}}    AS publisherKind,
    r.{{QbRedemption.publisherId}}      AS publisherId,
    sp.{{QbSponsor.company}}          AS sponsorCompany,
    r.{{QbRedemption.userId}}         AS userId,
    p.{{QbProfile.displayName}}       AS takerName,
    r.{{QbRedemption.amount}}         AS amount,
    r.{{QbRedemption.description}}    AS description,
    r.{{QbRedemption.status}}         AS status,
    r.{{QbRedemption.dueAt}}          AS dueAt,
    r.{{QbRedemption.expiresAt}}      AS expiresAt,
    r.{{QbRedemption.claimKind}}      AS claimKind,
    r.{{QbRedemption.claimedAt}}      AS claimedAt,
    r.{{QbRedemption.confirmBy}}      AS confirmBy,
    r.{{QbRedemption.redeemedAt}}     AS redeemedAt,
    r.{{QbRedemption.redeemedBy}}     AS redeemedBy,
    r.{{QbRedemption.voidedBy}}       AS voidedBy,
    r.{{QbRedemption.voidNote}}       AS voidNote
FROM {{QbRedemption}} r
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = r.{{QbRedemption.publicationId}}
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = r.{{QbRedemption.publisherId}}
LEFT JOIN {{QbProfile}} p ON p.{{QbProfile.userId}} = r.{{QbRedemption.userId}}
WHERE (CAST(:q AS text) IS NULL
       OR strpos(lower(r.{{QbRedemption.code}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(sp.{{QbSponsor.company}}, '')), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(p.{{QbProfile.displayName}}, '')), lower(CAST(:q AS text))) > 0)
