/*---
id: qb.system.analytics-due
description: The publications the analytics job reads - running, ended lately, or ended and not yet recounted whole
entities: [QbPublicationCard, QbAnalyticsCursor]
datasets:
  QbPublicationCard: urn:jabiz:dataset:stats:QbPublicationCard
  QbAnalyticsCursor: urn:jabiz:dataset:default:QbAnalyticsCursor
params:
  recent: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: Publications ended after this time are read }
results:
  publicationId: { from: QbPublicationCard.publicationId }
list:
  key: [publicationId]
permissions: [qb.stats.system]
---*/
-- Three index ranges (stats.AnalyticsRefresh orders and chunks them): the running cards by state, the cards ended
-- after "recent", and the cursors not yet finalized whose publication has ended (the recount waits for its attempts to
-- finish, however long after the end).
SELECT k.{{QbPublicationCard.publicationId}} AS publicationId
FROM {{QbPublicationCard}} k
WHERE k.{{QbPublicationCard.status}} IN ('LIVE', 'PAUSED', 'FUNDS_SHORT')
UNION
SELECT k.{{QbPublicationCard.publicationId}} AS publicationId
FROM {{QbPublicationCard}} k
WHERE k.{{QbPublicationCard.status}} = 'ENDED' AND k.{{QbPublicationCard.endedAt}} > CAST(:recent AS timestamptz)
UNION
SELECT k.{{QbPublicationCard.publicationId}} AS publicationId
FROM {{QbAnalyticsCursor}} c
JOIN {{QbPublicationCard}} k ON k.{{QbPublicationCard.publicationId}} = c.{{QbAnalyticsCursor.publicationId}}
WHERE c.{{QbAnalyticsCursor.finalizedAt}} IS NULL AND k.{{QbPublicationCard.status}} = 'ENDED'
