/*---
id: qb.app.inbox
description: >-
  The taker's inbox (U-60 - U-63; Q7c D-Q7-9), newest first - their own messages and the announcements and sponsors'
  notices that match them now, with what they read; filtered by kind, unread, text and time, or one item
entities: [QbMessage, QbBroadcastFeed, QbBroadcastTarget, QbBroadcastReceipt, QbInboxState, QbProfile, QbCountry, QbSponsor, QbPublicationCard, QbPublicationOwner]
datasets:
  QbMessage: urn:jabiz:dataset:me:QbMessage
  QbBroadcastFeed: urn:jabiz:dataset:default:QbBroadcastFeed
  QbBroadcastTarget: urn:jabiz:dataset:me:QbBroadcastTarget
  QbBroadcastReceipt: urn:jabiz:dataset:me:QbBroadcastReceipt
  QbInboxState: urn:jabiz:dataset:me:QbInboxState
  QbProfile: urn:jabiz:dataset:me:QbProfile
  QbCountry: urn:jabiz:dataset:default:QbCountry
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbPublicationOwner: urn:jabiz:dataset:me:QbPublicationOwner
params:
  category: { kind: { type: text, maxLength: 10 }, description: "Only SYSTEM (system messages, announcements and notices), PROMO, REWARD, REMINDER or SPONSOR (a sponsor's own messages, Q68)" }
  unread:   { kind: { type: bool }, description: Only the unread ones }
  q:        { kind: { type: text, maxLength: 100 }, description: "Only those whose title or text contains this, in the language lang or English" }
  lang:     { kind: { type: text, maxLength: 5 }, description: "The interface's language (en, zh, ja)" }
  from:     { kind: { type: temporal, role: EVENT_TIME }, description: Only those that came at or after this time }
  to:       { kind: { type: temporal, role: EVENT_TIME }, description: Only those that came before this time }
  itemKind: { kind: { type: text, maxLength: 10 }, description: "One item: MESSAGE or BROADCAST" }
  itemId:   { kind: { type: text, maxLength: 36 }, description: "One item: its id" }
  enabled:  { kind: { type: bool }, description: "false: nothing (the mark process runs one inbox of the two)" }
  languages: { kind: { type: text, maxLength: 20 }, description: "Only the sponsors' notices about publications in these languages (Q59b), comma-separated (ja, zh, en): the taker's switches, or their interface language; all when not given" }
results:
  itemKind:      { kind: { type: text, maxLength: 10 } }
  itemId:        { kind: { type: text, maxLength: 36 } }
  type:          { kind: { type: text, maxLength: 10 } }
  category:      { kind: { type: text, maxLength: 10 } }
  kind:          { kind: { type: text, maxLength: 30 } }
  sender:        { kind: { type: text, maxLength: 10 } }
  sponsorName:   { from: QbSponsor.company }
  title:         { from: QbMessage.title }
  body:          { from: QbMessage.body }
  markdown:      { kind: { type: bool } }
  publicationId: { from: QbMessage.publicationId }
  action:        { kind: { type: text, maxLength: 20 } }
  actionId:      { kind: { type: text, maxLength: 36 } }
  at:            { kind: { type: temporal, role: EVENT_TIME } }
  expiresAt:     { kind: { type: temporal, role: EVENT_TIME } }
  readAt:        { kind: { type: temporal, role: EVENT_TIME } }
  clickedAt:     { kind: { type: temporal, role: EVENT_TIME } }
  unread:        { kind: { type: bool } }
list:
  sorts: [at]
  defaultSort: { field: at, asc: false }
  key: [itemKind, itemId]
permissions: [qb.me]
---*/
-- The matching of D-Q7-9 (Q7-9), written here, in qb.app.inbox-unread and in qb.system.broadcast-audience (the same
-- conditions; the case table of the test InboxMatchIT checks all three): an announcement sent to the app and live (not
-- withdrawn, not expired: the minute's job qb.broadcast-expire switches those off, so the partial index of the live
-- rows holds only the current ones and no time comes from the client, review H1 and L1), aimed at everyone, at a region of the caller's country (every country is in GLOBAL; no
-- country: GLOBAL only) or at the caller by name; a promotion only to someone registered (their profile made) when it
-- was sent and who did not turn promotions off (Q7-11). A sponsor's notice only while its publication is scheduled
-- or live (hidden while paused or short of funds, review M1; switched off when it ends), to takers in the
-- publication's regions, never to the sponsor themselves. A personal message is plain text (review H2: its values,
-- such as a publication's name, are the sponsor's words). The caller's own
-- rows only (the me datasets: messages, receipts, names, state, profile), the country from their profile, never from
-- a parameter. Unread: neither opened nor before "all read". The text is found as written in the interface's language
-- or English: strpos takes it literally, so % _ and \ match themselves.
WITH me AS (
    SELECT
        (SELECT p.{{QbProfile.createdAt}} FROM {{QbProfile}} p LIMIT 1) AS registered_at,
        coalesce((SELECT co.{{QbCountry.regions}} FROM {{QbProfile}} p
                  JOIN {{QbCountry}} co ON co.{{QbCountry.code}} = p.{{QbProfile.countryCode}} LIMIT 1),
                 'GLOBAL') AS me_regions,
        (SELECT s.{{QbInboxState.readAllBefore}} FROM {{QbInboxState}} s LIMIT 1) AS all_read_before,
        coalesce((SELECT s.{{QbInboxState.promoOff}} FROM {{QbInboxState}} s LIMIT 1), false) AS no_promo
),
items AS (
    SELECT
        CAST('MESSAGE' AS varchar(10))            AS item_kind,
        CAST(m.{{QbMessage.messageId}} AS varchar(36)) AS item_id,
        CAST(m.{{QbMessage.type}} AS varchar(10)) AS type,
        -- Q68: a sponsor's own messages (a cash reward, a claim, a digital prize: those leading to the sponsor
        -- console) are the sponsor's category, whatever their type.
        CAST(CASE WHEN m.{{QbMessage.action}} IN ('CASH', 'REDEEM', 'DIGITAL') THEN 'SPONSOR'
                  ELSE m.{{QbMessage.type}} END AS varchar(10)) AS category,
        CAST(m.{{QbMessage.kind}} AS varchar(30)) AS kind,
        CAST(NULL AS varchar(10))                 AS sender,
        CAST(NULL AS varchar(200))                AS sponsor_name,
        m.{{QbMessage.title}}                     AS title,
        m.{{QbMessage.body}}                      AS body,
        false                                     AS markdown,
        m.{{QbMessage.publicationId}}             AS pub_id,
        CAST(m.{{QbMessage.action}} AS varchar(20)) AS action,
        CAST(m.{{QbMessage.actionId}} AS varchar(36)) AS act_id,
        m.{{QbMessage.createdAt}}                 AS at,
        CAST(NULL AS timestamptz)                 AS expires_time,
        m.{{QbMessage.readAt}}                    AS read_time,
        m.{{QbMessage.clickedAt}}                 AS clicked_time,
        (m.{{QbMessage.readAt}} IS NULL
         AND (me.all_read_before IS NULL OR m.{{QbMessage.createdAt}} > me.all_read_before)) AS unread
    FROM {{QbMessage}} m CROSS JOIN me
    UNION ALL
    SELECT
        CAST('BROADCAST' AS varchar(10)),
        CAST(f.{{QbBroadcastFeed.broadcastId}} AS varchar(36)),
        CAST(f.{{QbBroadcastFeed.type}} AS varchar(10)),
        CAST(CASE WHEN f.{{QbBroadcastFeed.type}} = 'PROMO' THEN 'PROMO' ELSE 'SYSTEM' END AS varchar(10)),
        CAST(NULL AS varchar(30)),
        CAST(f.{{QbBroadcastFeed.senderKind}} AS varchar(10)),
        CAST(sp.{{QbSponsor.company}} AS varchar(200)),
        f.{{QbBroadcastFeed.title}},
        f.{{QbBroadcastFeed.body}},
        f.{{QbBroadcastFeed.senderKind}} = 'PLATFORM',
        f.{{QbBroadcastFeed.publicationId}},
        CAST(NULL AS varchar(20)),
        CAST(NULL AS varchar(36)),
        f.{{QbBroadcastFeed.sentAt}},
        f.{{QbBroadcastFeed.expiresAt}},
        r.{{QbBroadcastReceipt.readAt}},
        r.{{QbBroadcastReceipt.clickedAt}},
        (r.{{QbBroadcastReceipt.readAt}} IS NULL
         AND (me.all_read_before IS NULL OR f.{{QbBroadcastFeed.sentAt}} > me.all_read_before))
    FROM {{QbBroadcastFeed}} f
    CROSS JOIN me
    LEFT JOIN {{QbBroadcastReceipt}} r ON r.{{QbBroadcastReceipt.broadcastId}} = f.{{QbBroadcastFeed.broadcastId}}
    LEFT JOIN {{QbSponsor}} sp ON f.{{QbBroadcastFeed.senderKind}} = 'SPONSOR'
        AND sp.{{QbSponsor.userId}} = f.{{QbBroadcastFeed.senderId}}
    LEFT JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = f.{{QbBroadcastFeed.publicationId}}
    WHERE f.{{QbBroadcastFeed.live}} AND f.{{QbBroadcastFeed.audience}} = 'APP'
      AND (f.{{QbBroadcastFeed.targetMode}} = 'ALL'
           OR f.{{QbBroadcastFeed.targetMode}} = 'REGIONS'
              AND string_to_array(f.{{QbBroadcastFeed.regions}}, ',') && string_to_array(me.me_regions, ',')
           OR f.{{QbBroadcastFeed.targetMode}} = 'USERS'
              AND EXISTS (SELECT 1 FROM {{QbBroadcastTarget}} t
                          WHERE t.{{QbBroadcastTarget.broadcastId}} = f.{{QbBroadcastFeed.broadcastId}}))
      AND (f.{{QbBroadcastFeed.type}} <> 'PROMO'
           OR NOT me.no_promo AND me.registered_at IS NOT NULL
              AND me.registered_at <= f.{{QbBroadcastFeed.sentAt}})
      AND (f.{{QbBroadcastFeed.senderKind}} <> 'SPONSOR'
           OR c.{{QbPublicationCard.status}} IN ('SCHEDULED', 'LIVE')
              AND string_to_array(c.{{QbPublicationCard.regions}}, ',') && string_to_array(me.me_regions, ',')
              -- Q59b: a sponsor's notice is about a publication: only those of the languages the taker sees.
              AND (CAST(:languages AS text) IS NULL
                   OR c.{{QbPublicationCard.language}} = ANY(string_to_array(CAST(:languages AS text), ',')))
              AND NOT EXISTS (SELECT 1 FROM {{QbPublicationOwner}} own
                              WHERE own.{{QbPublicationOwner.publicationId}} = f.{{QbBroadcastFeed.publicationId}}))
)
SELECT
    i.item_kind      AS itemKind,
    i.item_id        AS itemId,
    i.type           AS type,
    i.category       AS category,
    i.kind           AS kind,
    i.sender         AS sender,
    i.sponsor_name   AS sponsorName,
    i.title          AS title,
    i.body           AS body,
    i.markdown       AS markdown,
    i.pub_id AS publicationId,
    i.action         AS action,
    i.act_id      AS actionId,
    i.at             AS at,
    i.expires_time     AS expiresAt,
    i.read_time        AS readAt,
    i.clicked_time     AS clickedAt,
    i.unread         AS unread
FROM items i
WHERE coalesce(CAST(:enabled AS boolean), true)
  AND (CAST(:category AS text) IS NULL OR i.category = CAST(:category AS text))
  AND (CAST(:unread AS boolean) IS NULL OR NOT CAST(:unread AS boolean) OR i.unread)
  AND (CAST(:from AS timestamptz) IS NULL OR i.at >= CAST(:from AS timestamptz))
  AND (CAST(:to AS timestamptz) IS NULL OR i.at < CAST(:to AS timestamptz))
  AND (CAST(:itemKind AS text) IS NULL OR i.item_kind = CAST(:itemKind AS text))
  AND (CAST(:itemId AS text) IS NULL OR i.item_id = CAST(:itemId AS text))
  AND (CAST(:q AS text) IS NULL
       OR strpos(lower(coalesce(i.title ->> coalesce(CAST(:lang AS text), 'en'), '')), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(i.body ->> coalesce(CAST(:lang AS text), 'en'), '')), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(i.title ->> 'en', '')), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(i.body ->> 'en', '')), lower(CAST(:q AS text))) > 0)
