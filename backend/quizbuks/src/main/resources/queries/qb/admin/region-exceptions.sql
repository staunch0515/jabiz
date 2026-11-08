/*---
id: qb.admin.region-exceptions
description: >-
  The exceptions to the rule on foreign videos (F-S11, A3-15): which publication, or every publication of which
  sponsor, mainland China may answer although its videos are foreign, why, until when, and whether it is in force at
  the time given (not revoked, not past its last day, UTC). Headquarters reads every sponsor's, an operator its own
  sponsors' (the requester, 2026-11-07)
entities: [QbRegionException, QbPublicationCard, QbSponsor, QbSponsorOperator, QbOperator]
datasets:
  QbRegionException: urn:jabiz:dataset:default:QbRegionException
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperator: urn:jabiz:dataset:default:QbOperator
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time whose day decides what is in force }
  inForce: { kind: { type: bool }, description: "If true, only the exceptions in force; all when not given" }
results:
  exceptionId:     { from: QbRegionException.exceptionId }
  kind:            { from: QbRegionException.kind }
  publicationId:   { from: QbRegionException.publicationId }
  publicationName: { from: QbPublicationCard.name }
  foreignVideo:    { from: QbPublicationCard.foreignVideo }
  sponsorId:       { from: QbRegionException.sponsorId }
  ownerId:         { from: QbRegionException.ownerId }
  company:         { from: QbSponsor.company }
  # F-S1b: the sponsor's operator now, for the platform's filter by operator.
  operatorId:      { from: QbSponsorOperator.operatorId }
  operatorName:    { from: QbOperator.name }
  reason:          { from: QbRegionException.reason }
  expiresOn:       { from: QbRegionException.expiresOn }
  revoked:         { from: QbRegionException.revoked }
  revokeReason:    { from: QbRegionException.revokeReason }
  inForce:         { kind: { type: bool } }
  createdAt:       { from: QbRegionException.createdAt }
  createdBy:       { from: QbRegionException.createdBy }
  changedAt:       { from: QbRegionException.changedAt }
  changedBy:       { from: QbRegionException.changedBy }
list:
  filters: [kind, publicationId, sponsorId, ownerId, company, revoked, operatorId]
  sorts:   [createdAt, expiresOn]
  defaultSort: { field: createdAt, asc: false }
  key: [exceptionId]
permissions: [qb.admin.region.read]
---*/
-- In force as region.RegionRules.Exception#inForce decides it: not revoked and its last day (UTC) not before the day
-- of :at. The publication's name from its card (none before it went on); the company of the sponsor it belongs to
-- (the onboarding's current one). For an operator's session the exceptions are its own sponsors' only (Q59c-2).
-- The card and the onboarding are limited the same way, so an operator's exception never misses its names.
SELECT * FROM (
    SELECT
        e.{{QbRegionException.exceptionId}}   AS exceptionId,
        e.{{QbRegionException.kind}}          AS kind,
        e.{{QbRegionException.publicationId}} AS publicationId,
        c.{{QbPublicationCard.name}}          AS publicationName,
        c.{{QbPublicationCard.foreignVideo}}  AS foreignVideo,
        e.{{QbRegionException.sponsorId}}     AS sponsorId,
        e.{{QbRegionException.ownerId}}       AS ownerId,
        (SELECT max(p.{{QbSponsor.company}}) FROM {{QbSponsor}} p
         WHERE p.{{QbSponsor.userId}} = e.{{QbRegionException.ownerId}}) AS company,
        e.{{QbRegionException.reason}}        AS reason,
        e.{{QbRegionException.expiresOn}}     AS expiresOn,
        e.{{QbRegionException.revoked}}       AS revoked,
        e.{{QbRegionException.revokeReason}}  AS revokeReason,
        (NOT e.{{QbRegionException.revoked}}
            AND (e.{{QbRegionException.expiresOn}} IS NULL
                OR e.{{QbRegionException.expiresOn}} >= CAST(CAST(:at AS timestamptz) AT TIME ZONE 'UTC' AS date))) AS inForce,
        e.{{QbRegionException.createdAt}}     AS createdAt,
        e.{{QbRegionException.createdBy}}     AS createdBy,
        e.{{QbRegionException.changedAt}}     AS changedAt,
        e.{{QbRegionException.changedBy}}     AS changedBy,
        qso.{{QbSponsorOperator.operatorId}}  AS operatorId,
        qop.{{QbOperator.name}}               AS operatorName
    FROM {{QbRegionException}} e
    LEFT JOIN {{QbPublicationCard}} c
        ON CAST(c.{{QbPublicationCard.publicationId}} AS text) = e.{{QbRegionException.publicationId}}
    LEFT JOIN {{QbSponsorOperator}} qso ON qso.{{QbSponsorOperator.userId}} = e.{{QbRegionException.ownerId}}
    LEFT JOIN {{QbOperator}} qop ON qop.{{QbOperator.operatorId}} = qso.{{QbSponsorOperator.operatorId}}
) x
WHERE (CAST(:inForce AS boolean) IS NULL OR x.inForce = CAST(:inForce AS boolean))
