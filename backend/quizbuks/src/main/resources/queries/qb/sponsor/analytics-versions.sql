/*---
id: qb.sponsor.analytics.versions
description: >-
  The versions of its quiz one of the sponsor's publications ran on (Q51b): the one it runs on and those it switched
  from, newest first, for choosing whose question analysis to show
entities: [QbPublicationCard, QbPublicationPastVersion, QbQuizVersion]
datasets:
  QbPublicationCard: urn:jabiz:dataset:sponsor:QbPublicationCard
  QbPublicationPastVersion: urn:jabiz:dataset:default:QbPublicationPastVersion
  QbQuizVersion: urn:jabiz:dataset:default:QbQuizVersion
params:
  publicationId: { like: QbPublicationCard.publicationId, required: true, description: The publication }
results:
  versionId:     { from: QbQuizVersion.versionId }
  versionNumber: { from: QbQuizVersion.versionNumber }
  label:         { from: QbQuizVersion.label }
  title:         { from: QbQuizVersion.title }
  current:       { kind: { type: bool } }
  replacedAt:    { from: QbPublicationPastVersion.replacedAt }
list:
  sorts: [versionNumber]
  defaultSort: { field: versionNumber, asc: false }
  key: [versionId]
permissions: [qb.stats.read]
---*/
-- Only a publication of the sponsor's own. A version it switched away from and back to is listed once, as the
-- current one, with the last time it was replaced.
WITH card AS (
    SELECT p.{{QbPublicationCard.publicationId}} AS pid, p.{{QbPublicationCard.versionId}} AS vid
    FROM {{QbPublicationCard}} p
    WHERE p.{{QbPublicationCard.publicationId}} = :publicationId),
ran AS (
    SELECT c.vid, true AS is_current, CAST(NULL AS timestamptz) AS replaced FROM card c
    UNION ALL
    SELECT x.{{QbPublicationPastVersion.versionId}}, false, x.{{QbPublicationPastVersion.replacedAt}}
    FROM card c
    JOIN {{QbPublicationPastVersion}} x ON x.{{QbPublicationPastVersion.publicationId}} = c.pid),
versions AS (
    SELECT r.vid, bool_or(r.is_current) AS is_current, max(r.replaced) AS replaced
    FROM ran r
    GROUP BY r.vid)
SELECT
    v.{{QbQuizVersion.versionId}}     AS versionId,
    v.{{QbQuizVersion.versionNumber}} AS versionNumber,
    v.{{QbQuizVersion.label}}         AS label,
    v.{{QbQuizVersion.title}}         AS title,
    r.is_current                      AS current,
    r.replaced                        AS replacedAt
FROM versions r
JOIN {{QbQuizVersion}} v ON v.{{QbQuizVersion.versionId}} = r.vid
