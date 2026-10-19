/*---
id: qb.sponsor.inbox
description: >-
  The platform's announcements to the sponsor (Q7c, Q7-9), newest first - those to every sponsor and those naming the
  caller, with what they read; or one item
entities: [QbBroadcastFeed, QbBroadcastTarget, QbBroadcastReceipt, QbInboxState, QbSponsor]
datasets:
  QbBroadcastFeed: urn:jabiz:dataset:default:QbBroadcastFeed
  QbBroadcastTarget: urn:jabiz:dataset:me:QbBroadcastTarget
  QbBroadcastReceipt: urn:jabiz:dataset:me:QbBroadcastReceipt
  QbInboxState: urn:jabiz:dataset:me:QbInboxState
  QbSponsor: urn:jabiz:dataset:sponsor:QbSponsor
params:
  unread:   { kind: { type: bool }, description: Only the unread ones }
  itemKind: { kind: { type: text, maxLength: 10 }, description: "One item: BROADCAST" }
  itemId:   { kind: { type: text, maxLength: 36 }, description: "One item: its id" }
  enabled:  { kind: { type: bool }, description: "false: nothing (the mark process runs one inbox of the two)" }
results:
  itemKind:      { kind: { type: text, maxLength: 10 } }
  itemId:        { kind: { type: text, maxLength: 36 } }
  type:          { kind: { type: text, maxLength: 10 } }
  title:         { from: QbBroadcastFeed.title }
  body:          { from: QbBroadcastFeed.body }
  publicationId: { from: QbBroadcastFeed.publicationId }
  at:            { kind: { type: temporal, role: EVENT_TIME } }
  expiresAt:     { kind: { type: temporal, role: EVENT_TIME } }
  readAt:        { kind: { type: temporal, role: EVENT_TIME } }
  unread:        { kind: { type: bool } }
list:
  sorts: [at]
  defaultSort: { field: at, asc: false }
  key: [itemId]
permissions: [qb.sponsor.me]
---*/
-- The matching of qb.app.inbox for the sponsor console's audience (InboxMatchIT): live (sent, neither withdrawn nor
-- expired: the minute's job switches the expired off, so no time comes from the client), to every sponsor or naming the caller; a promotion only to a sponsor approved when it was sent who did not
-- turn promotions off. The caller's own onboarding, names, receipts and state only (their datasets' scope).
WITH me AS (
    SELECT
        (SELECT s.{{QbSponsor.approvedAt}} FROM {{QbSponsor}} s
         WHERE s.{{QbSponsor.reviewStatus}} = 'APPROVED' LIMIT 1) AS approved_time,
        (SELECT s.{{QbInboxState.readAllBefore}} FROM {{QbInboxState}} s LIMIT 1) AS all_read_before,
        coalesce((SELECT s.{{QbInboxState.promoOff}} FROM {{QbInboxState}} s LIMIT 1), false) AS no_promo
)
SELECT
    CAST('BROADCAST' AS varchar(10))                      AS itemKind,
    CAST(f.{{QbBroadcastFeed.broadcastId}} AS varchar(36)) AS itemId,
    CAST(f.{{QbBroadcastFeed.type}} AS varchar(10))       AS type,
    f.{{QbBroadcastFeed.title}}                           AS title,
    f.{{QbBroadcastFeed.body}}                            AS body,
    f.{{QbBroadcastFeed.publicationId}}                   AS publicationId,
    f.{{QbBroadcastFeed.sentAt}}                          AS at,
    f.{{QbBroadcastFeed.expiresAt}}                       AS expiresAt,
    r.{{QbBroadcastReceipt.readAt}}                       AS readAt,
    (r.{{QbBroadcastReceipt.readAt}} IS NULL
     AND (me.all_read_before IS NULL OR f.{{QbBroadcastFeed.sentAt}} > me.all_read_before)) AS unread
FROM {{QbBroadcastFeed}} f
CROSS JOIN me
LEFT JOIN {{QbBroadcastReceipt}} r ON r.{{QbBroadcastReceipt.broadcastId}} = f.{{QbBroadcastFeed.broadcastId}}
WHERE coalesce(CAST(:enabled AS boolean), true)
  AND f.{{QbBroadcastFeed.live}} AND f.{{QbBroadcastFeed.audience}} = 'SPONSOR'
  AND (f.{{QbBroadcastFeed.targetMode}} = 'ALL'
       OR f.{{QbBroadcastFeed.targetMode}} = 'USERS'
          AND EXISTS (SELECT 1 FROM {{QbBroadcastTarget}} t
                      WHERE t.{{QbBroadcastTarget.broadcastId}} = f.{{QbBroadcastFeed.broadcastId}}))
  AND (f.{{QbBroadcastFeed.type}} <> 'PROMO'
       OR NOT me.no_promo AND me.approved_time IS NOT NULL AND me.approved_time <= f.{{QbBroadcastFeed.sentAt}})
  AND (CAST(:unread AS boolean) IS NULL OR NOT CAST(:unread AS boolean)
       OR r.{{QbBroadcastReceipt.readAt}} IS NULL
          AND (me.all_read_before IS NULL OR f.{{QbBroadcastFeed.sentAt}} > me.all_read_before))
  AND (CAST(:itemKind AS text) IS NULL OR CAST(:itemKind AS text) = 'BROADCAST')
  AND (CAST(:itemId AS text) IS NULL OR CAST(f.{{QbBroadcastFeed.broadcastId}} AS text) = CAST(:itemId AS text))
