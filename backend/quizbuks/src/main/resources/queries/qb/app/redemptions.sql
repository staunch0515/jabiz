/*---
id: qb.app.redemptions
description: "The taker's own prizes to collect at a sponsor's place (Q22b), newest first"
entities: [QbRedemption, QbPublicationCard, QbSponsor]
datasets:
  QbRedemption: urn:jabiz:dataset:taker:QbRedemption
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  open: { kind: { type: bool }, description: Only prizes not collected yet (due or claimed) }
results:
  redemptionId:    { from: QbRedemption.redemptionId }
  attemptId:       { from: QbRedemption.attemptId }
  code:            { from: QbRedemption.code }
  publicationId:   { from: QbRedemption.publicationId }
  publicationName: { from: QbPublicationCard.name }
  sponsorCompany:  { from: QbSponsor.company }
  amount:          { from: QbRedemption.amount }
  description:     { from: QbRedemption.description }
  status:          { from: QbRedemption.status }
  dueAt:           { from: QbRedemption.dueAt }
  expiresAt:       { from: QbRedemption.expiresAt }
  claimedAt:       { from: QbRedemption.claimedAt }
  confirmBy:       { from: QbRedemption.confirmBy }
  redeemedAt:      { from: QbRedemption.redeemedAt }
list:
  filters: [status, dueAt]
  sorts:   [dueAt, expiresAt, amount, status]
  defaultSort: { field: dueAt, asc: false }
  key: [redemptionId]
permissions: [qb.me]
---*/
SELECT
    r.{{QbRedemption.redemptionId}}  AS redemptionId,
    r.{{QbRedemption.attemptId}}     AS attemptId,
    r.{{QbRedemption.code}}          AS code,
    r.{{QbRedemption.publicationId}} AS publicationId,
    c.{{QbPublicationCard.name}}     AS publicationName,
    sp.{{QbSponsor.company}}         AS sponsorCompany,
    r.{{QbRedemption.amount}}        AS amount,
    r.{{QbRedemption.description}}   AS description,
    r.{{QbRedemption.status}}        AS status,
    r.{{QbRedemption.dueAt}}         AS dueAt,
    r.{{QbRedemption.expiresAt}}     AS expiresAt,
    r.{{QbRedemption.claimedAt}}     AS claimedAt,
    r.{{QbRedemption.confirmBy}}     AS confirmBy,
    r.{{QbRedemption.redeemedAt}}    AS redeemedAt
FROM {{QbRedemption}} r
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = r.{{QbRedemption.publicationId}}
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = r.{{QbRedemption.sponsorId}}
WHERE (CAST(:open AS boolean) IS NULL OR CAST(:open AS boolean) = false
       OR r.{{QbRedemption.status}} IN ('DUE', 'CLAIMED'))
