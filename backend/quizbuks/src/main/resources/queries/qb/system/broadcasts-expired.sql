/*---
id: qb.system.broadcasts-expired
description: The live rows of the feed that expired by the given time, and the sponsors' notices whose publication ended (the items of the job qb.broadcast-expire)
entities: [QbBroadcastFeed, QbPublicationCard]
datasets:
  QbBroadcastFeed: urn:jabiz:dataset:default:QbBroadcastFeed
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
params:
  at: { like: QbBroadcastFeed.expiresAt, required: true, description: The scheduled time of the run }
results:
  broadcastId: { from: QbBroadcastFeed.broadcastId }
list:
  sorts: [broadcastId]
  defaultSort: { field: broadcastId, asc: true }
  key: [broadcastId]
permissions: [qb.messaging.system]
---*/
-- Both halves through the partial index of the live rows (V15: audience, expires_at WHERE live), which holds only the
-- current ones once this job keeps it so (review H1): the expired by their end, and the notices (all to the app) whose
-- publication's card says it ended. QB_BROADCAST_EXPIRE reads each again under its lock.
SELECT f.{{QbBroadcastFeed.broadcastId}} AS broadcastId
FROM {{QbBroadcastFeed}} f
WHERE f.{{QbBroadcastFeed.live}} AND f.{{QbBroadcastFeed.audience}} IN ('APP', 'SPONSOR')
  AND f.{{QbBroadcastFeed.expiresAt}} <= :at
UNION
SELECT f.{{QbBroadcastFeed.broadcastId}}
FROM {{QbBroadcastFeed}} f
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = f.{{QbBroadcastFeed.publicationId}}
WHERE f.{{QbBroadcastFeed.live}} AND f.{{QbBroadcastFeed.audience}} = 'APP'
  AND f.{{QbBroadcastFeed.senderKind}} = 'SPONSOR' AND c.{{QbPublicationCard.status}} = 'ENDED'
