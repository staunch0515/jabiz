/*---
id: qb.sponsor.broadcasts
description: >-
  The sponsor's notices (M-60, M-61), newest first - their state, publication and time, and once sent about how many
  they reached and, as of the day's count, how many read, clicked through and then started the quiz; small counts are
  not shown
entities: [QbBroadcast, QbPublicationCard, QbBroadcastStats, SysParam]
datasets:
  QbBroadcast: urn:jabiz:dataset:sponsor:QbBroadcast
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbBroadcastStats: urn:jabiz:dataset:default:QbBroadcastStats
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  status:      { like: QbBroadcast.status, description: Only this state }
  broadcastId: { like: QbBroadcast.broadcastId, description: Only this notice }
results:
  broadcastId:      { from: QbBroadcast.broadcastId }
  status:           { from: QbBroadcast.status }
  title:            { from: QbBroadcast.title }
  body:             { from: QbBroadcast.body }
  publicationId:    { from: QbBroadcast.publicationId }
  publicationName:  { from: QbPublicationCard.name }
  regions:          { from: QbBroadcast.regions }
  scheduledAt:      { from: QbBroadcast.scheduledAt }
  sentAt:           { from: QbBroadcast.sentAt }
  expiresAt:        { from: QbBroadcast.expiresAt }
  audienceCount:    { kind: { type: numeric, precision: 9, scale: 0 } }
  snapshotAt:       { from: QbBroadcastStats.snapshotAt }
  reads:            { kind: { type: numeric, precision: 9, scale: 0 } }
  clicks:           { kind: { type: numeric, precision: 9, scale: 0 } }
  conversions:      { kind: { type: numeric, precision: 9, scale: 0 } }
  cancelReason:     { from: QbBroadcast.cancelReason }
  withdrawnAt:      { from: QbBroadcast.withdrawnAt }
  withdrawnByAdmin: { kind: { type: bool } }
  withdrawnBy:      { from: QbBroadcast.withdrawnBy }
  withdrawReason:   { from: QbBroadcast.withdrawReason }
  revision:         { from: QbBroadcast.revision }
  editedAt:         { from: QbBroadcast.editedAt }
list:
  sorts: [editedAt, sentAt]
  defaultSort: { field: editedAt, asc: false }
  key: [broadcastId]
permissions: [qb.broadcast.write]
---*/
-- The caller's notices only (the dataset's scope); a discarded draft is left out. The statistics of Q7-14 as the
-- daily job qb.broadcast-stats counted them (qb.system.broadcast-stats; review M2: a sponsor polling live counts beside
-- the reward rows of their transactions would see who clicked): null before the first count. Each counts people, at
-- most once; a count below five is null ("fewer than 5", messaging.BroadcastRules.MIN_GROUP); clicks and conversions
-- are null too while fewer than qb.broadcast.stats.min-audience (50 by default) were reached; the reach is rounded down
-- to tens below 1,000 and to hundreds above (null below ten). Who read or clicked is never given; the administrators
-- see the exact live figures (qb.admin.broadcasts). An administrator's withdrawal shows its reason; a ban's only its
-- code; which administrator is the audit's (review L3).
WITH limits AS (
    SELECT coalesce((SELECT CAST(max(p.{{SysParam.value}}) AS int) FROM {{SysParam}} p
                     WHERE p.{{SysParam.paramKey}} = 'qb.broadcast.stats.min-audience'
                       AND p.{{SysParam.value}} ~ '^[0-9]{1,6}$'), 50) AS min_audience
)
SELECT
    b.{{QbBroadcast.broadcastId}}   AS broadcastId,
    b.{{QbBroadcast.status}}        AS status,
    b.{{QbBroadcast.title}}         AS title,
    b.{{QbBroadcast.body}}          AS body,
    b.{{QbBroadcast.publicationId}} AS publicationId,
    c.{{QbPublicationCard.name}}    AS publicationName,
    b.{{QbBroadcast.regions}}       AS regions,
    b.{{QbBroadcast.scheduledAt}}   AS scheduledAt,
    b.{{QbBroadcast.sentAt}}        AS sentAt,
    b.{{QbBroadcast.expiresAt}}     AS expiresAt,
    CAST(CASE WHEN b.{{QbBroadcast.audienceCount}} IS NULL OR b.{{QbBroadcast.audienceCount}} < 10 THEN NULL
         WHEN b.{{QbBroadcast.audienceCount}} < 1000 THEN floor(b.{{QbBroadcast.audienceCount}} / 10) * 10
         ELSE floor(b.{{QbBroadcast.audienceCount}} / 100) * 100 END AS numeric(9, 0)) AS audienceCount,
    s.{{QbBroadcastStats.snapshotAt}} AS snapshotAt,
    CASE WHEN s.{{QbBroadcastStats.reads}} < 5 THEN NULL ELSE s.{{QbBroadcastStats.reads}} END AS reads,
    CASE WHEN s.{{QbBroadcastStats.clicks}} < 5 OR coalesce(b.{{QbBroadcast.audienceCount}}, 0) < l.min_audience
         THEN NULL ELSE s.{{QbBroadcastStats.clicks}} END AS clicks,
    CASE WHEN s.{{QbBroadcastStats.conversions}} < 5 OR coalesce(b.{{QbBroadcast.audienceCount}}, 0) < l.min_audience
         THEN NULL ELSE s.{{QbBroadcastStats.conversions}} END AS conversions,
    b.{{QbBroadcast.cancelReason}}  AS cancelReason,
    b.{{QbBroadcast.withdrawnAt}}   AS withdrawnAt,
    coalesce(b.{{QbBroadcast.withdrawnBy}} = 'ADMIN', false) AS withdrawnByAdmin,
    b.{{QbBroadcast.withdrawnBy}}   AS withdrawnBy,
    b.{{QbBroadcast.withdrawReason}} AS withdrawReason,
    b.{{QbBroadcast.revision}}      AS revision,
    b.{{QbBroadcast.editedAt}}      AS editedAt
FROM {{QbBroadcast}} b
CROSS JOIN limits l
LEFT JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = b.{{QbBroadcast.publicationId}}
LEFT JOIN {{QbBroadcastStats}} s ON s.{{QbBroadcastStats.broadcastId}} = b.{{QbBroadcast.broadcastId}}
WHERE (b.{{QbBroadcast.status}} <> 'CANCELLED' OR b.{{QbBroadcast.cancelReason}} IS NOT NULL)
  AND (CAST(:status AS text) IS NULL OR b.{{QbBroadcast.status}} = CAST(:status AS text))
  AND (CAST(:broadcastId AS uuid) IS NULL OR b.{{QbBroadcast.broadcastId}} = CAST(:broadcastId AS uuid))
