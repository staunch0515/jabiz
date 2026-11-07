/*---
id: qb.admin.region-exceptions
description: >-
  Headquarters' exceptions to the rule on foreign videos (F-S11, A3-15): which publication, or every publication of
  which sponsor, mainland China may answer although its videos are foreign, why, until when, and whether it is in force
  at the time given (not revoked, not past its last day, UTC)
entities: [QbRegionException, QbPublicationCard]
datasets:
  QbRegionException: urn:jabiz:dataset:default:QbRegionException
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
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
  filters: [kind, publicationId, sponsorId, revoked]
  sorts:   [createdAt, expiresOn]
  defaultSort: { field: createdAt, asc: false }
  key: [exceptionId]
permissions: [qb.admin.region.read]
---*/
-- In force as region.RegionRules.Exception#inForce decides it: not revoked and its last day (UTC) not before the day
-- of :at. The publication's name from its card (none before it went on).
SELECT * FROM (
    SELECT
        e.{{QbRegionException.exceptionId}}   AS exceptionId,
        e.{{QbRegionException.kind}}          AS kind,
        e.{{QbRegionException.publicationId}} AS publicationId,
        c.{{QbPublicationCard.name}}          AS publicationName,
        c.{{QbPublicationCard.foreignVideo}}  AS foreignVideo,
        e.{{QbRegionException.sponsorId}}     AS sponsorId,
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
        e.{{QbRegionException.changedBy}}     AS changedBy
    FROM {{QbRegionException}} e
    LEFT JOIN {{QbPublicationCard}} c
        ON CAST(c.{{QbPublicationCard.publicationId}} AS text) = e.{{QbRegionException.publicationId}}
) x
WHERE (CAST(:inForce AS boolean) IS NULL OR x.inForce = CAST(:inForce AS boolean))
