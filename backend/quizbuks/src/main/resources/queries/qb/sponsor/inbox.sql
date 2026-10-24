/*---
id: qb.sponsor.inbox
description: >-
  The sponsor's inbox (Q7c, Q7-9; Q22b), newest first - their own messages (cash rewards, prize claims) and the
  platform's announcements to every sponsor or naming the caller, with what they read; or one item
entities: [QbMessage, QbBroadcastFeed, QbBroadcastTarget, QbBroadcastReceipt, QbInboxState, QbSponsor]
datasets:
  QbMessage: urn:jabiz:dataset:me:QbMessage
  QbBroadcastFeed: urn:jabiz:dataset:default:QbBroadcastFeed
  QbBroadcastTarget: urn:jabiz:dataset:me:QbBroadcastTarget
  QbBroadcastReceipt: urn:jabiz:dataset:me:QbBroadcastReceipt
  QbInboxState: urn:jabiz:dataset:me:QbInboxState
  QbSponsor: urn:jabiz:dataset:sponsor:QbSponsor
params:
  unread:   { kind: { type: bool }, description: Only the unread ones }
  itemKind: { kind: { type: text, maxLength: 10 }, description: "One item: MESSAGE or BROADCAST" }
  itemId:   { kind: { type: text, maxLength: 36 }, description: "One item: its id" }
  enabled:  { kind: { type: bool }, description: "false: nothing (the mark process runs one inbox of the two)" }
results:
  itemKind:      { kind: { type: text, maxLength: 10 } }
  itemId:        { kind: { type: text, maxLength: 36 } }
  type:          { kind: { type: text, maxLength: 10 } }
  kind:          { kind: { type: text, maxLength: 30 } }
  title:         { from: QbBroadcastFeed.title }
  body:          { from: QbBroadcastFeed.body }
  markdown:      { kind: { type: bool } }
  publicationId: { from: QbBroadcastFeed.publicationId }
  action:        { kind: { type: text, maxLength: 20 } }
  actionId:      { kind: { type: text, maxLength: 36 } }
  at:            { kind: { type: temporal, role: EVENT_TIME } }
  expiresAt:     { kind: { type: temporal, role: EVENT_TIME } }
  readAt:        { kind: { type: temporal, role: EVENT_TIME } }
  unread:        { kind: { type: bool } }
list:
  sorts: [at]
  defaultSort: { field: at, asc: false }
  key: [itemKind, itemId]
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
SELECT * FROM (
    -- The sponsor's own messages (Q22: a cash reward confirmed or disputed, a decision; Q22b: a claim from their
    -- printed code), plain text in every language; the me dataset limits them to the caller.
    SELECT
        CAST('MESSAGE' AS varchar(10))                   AS itemKind,
        CAST(m.{{QbMessage.messageId}} AS varchar(36))   AS itemId,
        CAST(m.{{QbMessage.type}} AS varchar(10))        AS type,
        CAST(m.{{QbMessage.kind}} AS varchar(30))        AS kind,
        m.{{QbMessage.title}}                            AS title,
        m.{{QbMessage.body}}                             AS body,
        false                                            AS markdown,
        m.{{QbMessage.publicationId}}                    AS publicationId,
        CAST(m.{{QbMessage.action}} AS varchar(20))      AS action,
        CAST(m.{{QbMessage.actionId}} AS varchar(36))    AS actionId,
        m.{{QbMessage.createdAt}}                        AS at,
        CAST(NULL AS timestamptz)                        AS expiresAt,
        m.{{QbMessage.readAt}}                           AS readAt,
        (m.{{QbMessage.readAt}} IS NULL
         AND (me.all_read_before IS NULL OR m.{{QbMessage.createdAt}} > me.all_read_before)) AS unread
    FROM {{QbMessage}} m
    CROSS JOIN me
    WHERE coalesce(CAST(:enabled AS boolean), true)
      AND (CAST(:unread AS boolean) IS NULL OR NOT CAST(:unread AS boolean)
           OR m.{{QbMessage.readAt}} IS NULL
              AND (me.all_read_before IS NULL OR m.{{QbMessage.createdAt}} > me.all_read_before))
      AND (CAST(:itemKind AS text) IS NULL OR CAST(:itemKind AS text) = 'MESSAGE')
      AND (CAST(:itemId AS text) IS NULL OR m.{{QbMessage.messageId}} = CAST(:itemId AS text))
    UNION ALL
    SELECT
        CAST('BROADCAST' AS varchar(10)),
        CAST(f.{{QbBroadcastFeed.broadcastId}} AS varchar(36)),
        CAST(f.{{QbBroadcastFeed.type}} AS varchar(10)),
        CAST(NULL AS varchar(30)),
        f.{{QbBroadcastFeed.title}},
        f.{{QbBroadcastFeed.body}},
        true,
        f.{{QbBroadcastFeed.publicationId}},
        CAST(NULL AS varchar(20)),
        CAST(NULL AS varchar(36)),
        f.{{QbBroadcastFeed.sentAt}},
        f.{{QbBroadcastFeed.expiresAt}},
        r.{{QbBroadcastReceipt.readAt}},
        (r.{{QbBroadcastReceipt.readAt}} IS NULL
         AND (me.all_read_before IS NULL OR f.{{QbBroadcastFeed.sentAt}} > me.all_read_before))
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
) items
