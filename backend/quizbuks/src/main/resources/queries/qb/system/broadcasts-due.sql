/*---
id: qb.system.broadcasts-due
description: Announcements and notices whose scheduled time has come by the given time, earliest first (the items of the job qb.broadcast-dispatch)
entities: [QbBroadcastSchedule]
datasets:
  QbBroadcastSchedule: urn:jabiz:dataset:default:QbBroadcastSchedule
params:
  at: { like: QbBroadcastSchedule.dueAt, required: true, description: The scheduled time of the run }
results:
  broadcastId: { from: QbBroadcastSchedule.broadcastId }
  dueAt:       { from: QbBroadcastSchedule.dueAt }
list:
  sorts: [dueAt]
  defaultSort: { field: dueAt, asc: true }
  key: [broadcastId]
permissions: [qb.messaging.system]
---*/
-- Through the index on the due time (V17). QB_BROADCAST_DISPATCH reads the broadcast again under its locks and sends
-- it only while it is still scheduled.
SELECT s.{{QbBroadcastSchedule.broadcastId}} AS broadcastId, s.{{QbBroadcastSchedule.dueAt}} AS dueAt
FROM {{QbBroadcastSchedule}} s
WHERE s.{{QbBroadcastSchedule.dueAt}} <= :at
