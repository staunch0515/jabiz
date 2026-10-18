/*---
id: qb.sponsor.broadcasts
description: >-
  The sponsor's notices (M-60, M-61), newest first - their state, publication and time, and once sent how many they
  reached, read, clicked through and then started the quiz; counts below five are not shown
entities: [QbBroadcast, QbPublicationCard, QbBroadcastReceipt, QbAttemptStart]
datasets:
  QbBroadcast: urn:jabiz:dataset:sponsor:QbBroadcast
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbBroadcastReceipt: urn:jabiz:dataset:default:QbBroadcastReceipt
  QbAttemptStart: urn:jabiz:dataset:default:QbAttemptStart
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
  audienceCount:    { from: QbBroadcast.audienceCount }
  reads:            { kind: { type: numeric, precision: 9, scale: 0 } }
  clicks:           { kind: { type: numeric, precision: 9, scale: 0 } }
  conversions:      { kind: { type: numeric, precision: 9, scale: 0 } }
  cancelReason:     { from: QbBroadcast.cancelReason }
  withdrawnAt:      { from: QbBroadcast.withdrawnAt }
  withdrawnByAdmin: { kind: { type: bool } }
  withdrawReason:   { from: QbBroadcast.withdrawReason }
  revision:         { from: QbBroadcast.revision }
  editedAt:         { from: QbBroadcast.editedAt }
list:
  sorts: [editedAt, sentAt]
  defaultSort: { field: editedAt, asc: false }
  key: [broadcastId]
permissions: [qb.broadcast.write]
---*/
-- The caller's notices only (the dataset's scope); a discarded draft is left out. The statistics of Q7-14: read =
-- opened (not "mark all read"), clicked = "view the quiz", converted = clicked and then started the publication
-- (QbAttemptStart: who started what when, no answers or rewards). Each counts people, at most once; a count below five
-- is null (shown as "fewer than 5"), so that a notice to a small region does not tell which of its few takers read it
-- (messaging.BroadcastRules.MIN_GROUP). Who read or clicked is never given. An administrator's withdrawal shows its
-- reason; a ban's shows only its code.
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
    b.{{QbBroadcast.audienceCount}} AS audienceCount,
    CASE WHEN s.reads < 5 THEN NULL ELSE s.reads END             AS reads,
    CASE WHEN s.clicks < 5 THEN NULL ELSE s.clicks END           AS clicks,
    CASE WHEN s.conversions < 5 THEN NULL ELSE s.conversions END AS conversions,
    b.{{QbBroadcast.cancelReason}}  AS cancelReason,
    b.{{QbBroadcast.withdrawnAt}}   AS withdrawnAt,
    (b.{{QbBroadcast.withdrawnBy}} IS NOT NULL
     AND b.{{QbBroadcast.withdrawnBy}} <> b.{{QbBroadcast.senderId}}) AS withdrawnByAdmin,
    b.{{QbBroadcast.withdrawReason}} AS withdrawReason,
    b.{{QbBroadcast.revision}}      AS revision,
    b.{{QbBroadcast.editedAt}}      AS editedAt
FROM {{QbBroadcast}} b
LEFT JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = b.{{QbBroadcast.publicationId}}
LEFT JOIN LATERAL (
    SELECT
        count(r.{{QbBroadcastReceipt.readAt}})    AS reads,
        count(r.{{QbBroadcastReceipt.clickedAt}}) AS clicks,
        count(*) FILTER (WHERE r.{{QbBroadcastReceipt.clickedAt}} IS NOT NULL AND EXISTS (
            SELECT 1 FROM {{QbAttemptStart}} a
            WHERE a.{{QbAttemptStart.userId}} = r.{{QbBroadcastReceipt.userId}}
              AND a.{{QbAttemptStart.publicationId}} = b.{{QbBroadcast.publicationId}}
              AND a.{{QbAttemptStart.startedAt}} >= r.{{QbBroadcastReceipt.clickedAt}})) AS conversions
    FROM {{QbBroadcastReceipt}} r
    WHERE r.{{QbBroadcastReceipt.broadcastId}} = b.{{QbBroadcast.broadcastId}}) s ON true
WHERE (b.{{QbBroadcast.status}} <> 'CANCELLED' OR b.{{QbBroadcast.cancelReason}} IS NOT NULL)
  AND (CAST(:status AS text) IS NULL OR b.{{QbBroadcast.status}} = CAST(:status AS text))
  AND (CAST(:broadcastId AS uuid) IS NULL OR b.{{QbBroadcast.broadcastId}} = CAST(:broadcastId AS uuid))
