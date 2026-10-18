/*---
id: qb.system.broadcast-stats
description: The sponsors' notices sent within the last 90 days whose counts differ from their snapshot, with the counts (the items of the daily job qb.broadcast-stats)
entities: [QbBroadcastFeed, QbBroadcastReceipt, QbAttemptStart, QbBroadcastStats]
datasets:
  QbBroadcastFeed: urn:jabiz:dataset:default:QbBroadcastFeed
  QbBroadcastReceipt: urn:jabiz:dataset:default:QbBroadcastReceipt
  QbAttemptStart: urn:jabiz:dataset:default:QbAttemptStart
  QbBroadcastStats: urn:jabiz:dataset:default:QbBroadcastStats
params:
  at: { like: QbBroadcastFeed.sentAt, required: true, description: The scheduled time of the run }
results:
  broadcastId: { from: QbBroadcastFeed.broadcastId }
  reads:       { kind: { type: numeric, precision: 9, scale: 0 } }
  clicks:      { kind: { type: numeric, precision: 9, scale: 0 } }
  conversions: { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [broadcastId]
  defaultSort: { field: broadcastId, asc: true }
  key: [broadcastId]
permissions: [qb.messaging.system]
---*/
-- The statistics of Q7-14, counted once a day for the sponsor's list (review M2: never live beside the reward rows):
-- read = opened (not "mark all read"), clicked = "view the quiz", converted = clicked and then started the publication
-- (QbAttemptStart: who started what when, no answers or rewards); each counts people, at most once. Only the rows that
-- changed (or have none yet) are items, so a quiet day writes nothing. Notices older than 90 days keep their last
-- counts.
WITH counted AS (
    SELECT
        f.{{QbBroadcastFeed.broadcastId}} AS notice_id,
        count(r.{{QbBroadcastReceipt.readAt}})    AS n_reads,
        count(r.{{QbBroadcastReceipt.clickedAt}}) AS n_clicks,
        count(*) FILTER (WHERE r.{{QbBroadcastReceipt.clickedAt}} IS NOT NULL AND EXISTS (
            SELECT 1 FROM {{QbAttemptStart}} a
            WHERE a.{{QbAttemptStart.userId}} = r.{{QbBroadcastReceipt.userId}}
              AND a.{{QbAttemptStart.publicationId}} = f.{{QbBroadcastFeed.publicationId}}
              AND a.{{QbAttemptStart.startedAt}} >= r.{{QbBroadcastReceipt.clickedAt}})) AS n_conversions
    FROM {{QbBroadcastFeed}} f
    LEFT JOIN {{QbBroadcastReceipt}} r ON r.{{QbBroadcastReceipt.broadcastId}} = f.{{QbBroadcastFeed.broadcastId}}
    WHERE f.{{QbBroadcastFeed.senderKind}} = 'SPONSOR'
      AND f.{{QbBroadcastFeed.sentAt}} > CAST(:at AS timestamptz) - interval '90 days'
      AND f.{{QbBroadcastFeed.sentAt}} <= :at
    GROUP BY f.{{QbBroadcastFeed.broadcastId}})
SELECT
    c.notice_id                         AS broadcastId,
    CAST(c.n_reads AS numeric(9, 0))       AS reads,
    CAST(c.n_clicks AS numeric(9, 0))      AS clicks,
    CAST(c.n_conversions AS numeric(9, 0)) AS conversions
FROM counted c
LEFT JOIN {{QbBroadcastStats}} s ON s.{{QbBroadcastStats.broadcastId}} = c.notice_id
WHERE s.{{QbBroadcastStats.broadcastId}} IS NULL
   OR s.{{QbBroadcastStats.reads}} <> c.n_reads OR s.{{QbBroadcastStats.clicks}} <> c.n_clicks
   OR s.{{QbBroadcastStats.conversions}} <> c.n_conversions
