/*---
id: qb.app.inbox-unread
description: >-
  How many messages and announcements of the taker's inbox are unread (the navigation's badge, U-61), each counted to
  at most 99
entities: [QbMessage, QbBroadcastFeed, QbBroadcastTarget, QbBroadcastReceipt, QbInboxState, QbProfile, QbCountry, QbPublicationCard, QbPublicationOwner]
datasets:
  QbMessage: urn:jabiz:dataset:me:QbMessage
  QbBroadcastFeed: urn:jabiz:dataset:default:QbBroadcastFeed
  QbBroadcastTarget: urn:jabiz:dataset:me:QbBroadcastTarget
  QbBroadcastReceipt: urn:jabiz:dataset:me:QbBroadcastReceipt
  QbInboxState: urn:jabiz:dataset:me:QbInboxState
  QbProfile: urn:jabiz:dataset:me:QbProfile
  QbCountry: urn:jabiz:dataset:default:QbCountry
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbPublicationOwner: urn:jabiz:dataset:me:QbPublicationOwner
params:
  withOldest: { kind: { type: bool }, description: "Also when the oldest unread broadcast was sent (QB_MESSAGE_MARK's read-through mark)" }
  languages: { kind: { type: text, maxLength: 20 }, description: "Only the sponsors' notices about publications in these languages (Q59b), comma-separated (ja, zh, en): the taker's switches, or their interface language; all when not given" }
results:
  messages:     { kind: { type: numeric, precision: 3, scale: 0 } }
  broadcasts:   { kind: { type: numeric, precision: 3, scale: 0 } }
  oldestUnread: { kind: { type: temporal, role: EVENT_TIME } }
permissions: [qb.me]
---*/
-- The conditions of qb.app.inbox (D-Q7-9; checked against it by InboxMatchIT), counting the unread only: the pages ask on entering,
-- when the app comes back to the front and every five minutes, so each count stops at 99 (the badge shows "99+"). The
-- unread messages are found through their partial index (V17). The broadcasts are the live ones only (the expiry job)
-- and of those only the ones sent at or after the reader's read-through mark: the ones before it were all read one by
-- one, so a reader who read everything costs no lookup per broadcast (review H1; QB_MESSAGE_MARK keeps the mark with
-- oldestUnread, which only it asks for). A broadcast that becomes visible later with an earlier time (a resumed
-- publication's notice, a promotion turned back on, a new country) is in the inbox as unread but not in the badge.
WITH me AS (
    SELECT
        (SELECT p.{{QbProfile.createdAt}} FROM {{QbProfile}} p LIMIT 1) AS registered_at,
        coalesce((SELECT co.{{QbCountry.regions}} FROM {{QbProfile}} p
                  JOIN {{QbCountry}} co ON co.{{QbCountry.code}} = p.{{QbProfile.countryCode}} LIMIT 1),
                 'GLOBAL') AS me_regions,
        (SELECT s.{{QbInboxState.readAllBefore}} FROM {{QbInboxState}} s LIMIT 1) AS all_read_before,
        (SELECT s.{{QbInboxState.readThrough}} FROM {{QbInboxState}} s LIMIT 1) AS through_mark,
        coalesce((SELECT s.{{QbInboxState.promoOff}} FROM {{QbInboxState}} s LIMIT 1), false) AS no_promo
),
unread_feed AS NOT MATERIALIZED (
        SELECT f.{{QbBroadcastFeed.sentAt}} AS sent
        FROM {{QbBroadcastFeed}} f
        CROSS JOIN me
        LEFT JOIN {{QbBroadcastReceipt}} r ON r.{{QbBroadcastReceipt.broadcastId}} = f.{{QbBroadcastFeed.broadcastId}}
        LEFT JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = f.{{QbBroadcastFeed.publicationId}}
        WHERE f.{{QbBroadcastFeed.live}} AND f.{{QbBroadcastFeed.audience}} = 'APP'
          AND (me.through_mark IS NULL OR f.{{QbBroadcastFeed.sentAt}} >= me.through_mark)
          AND r.{{QbBroadcastReceipt.readAt}} IS NULL
          AND (me.all_read_before IS NULL OR f.{{QbBroadcastFeed.sentAt}} > me.all_read_before)
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
                  -- Q59b: as the inbox's list.
                  AND (CAST(:languages AS text) IS NULL
                       OR string_to_array(coalesce(c.{{QbPublicationCard.languages}}, c.{{QbPublicationCard.language}}), ',')
                          && string_to_array(CAST(:languages AS text), ','))
                  AND NOT EXISTS (SELECT 1 FROM {{QbPublicationOwner}} own
                                  WHERE own.{{QbPublicationOwner.publicationId}} = f.{{QbBroadcastFeed.publicationId}}))
)
SELECT
    (SELECT count(*) FROM (
        SELECT 1 FROM {{QbMessage}} m CROSS JOIN me
        WHERE m.{{QbMessage.readAt}} IS NULL
          AND (me.all_read_before IS NULL OR m.{{QbMessage.createdAt}} > me.all_read_before)
        LIMIT 99) x) AS messages,
    (SELECT count(*) FROM (SELECT 1 FROM unread_feed LIMIT 99) y) AS broadcasts,
    CASE WHEN coalesce(CAST(:withOldest AS boolean), false) THEN (SELECT min(u.sent) FROM unread_feed u) END
        AS oldestUnread
