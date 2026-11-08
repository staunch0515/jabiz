/*---
id: qb.sponsor.my-shared-videos
description: >-
  F-S12: the videos the sponsor shared, newest first, with their review's result, a withdrawal or takedown and its
  reason, and how many publications use each now (any sponsor's, on and not ended)
entities: [QbSharedVideo, QbPublicationCard]
datasets:
  QbSharedVideo: urn:jabiz:dataset:me:QbSharedVideo
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
params:
  assetId: { kind: { type: text, maxLength: 36 }, description: "Only the shares of this asset" }
results:
  sharedVideoId:  { from: QbSharedVideo.sharedVideoId }
  assetId:        { from: QbSharedVideo.assetId }
  title:          { kind: { type: text } }
  url:            { from: QbSharedVideo.url }
  durationSec:    { from: QbSharedVideo.durationSec }
  vertical:       { from: QbSharedVideo.vertical }
  topics:         { from: QbSharedVideo.topics }
  status:         { from: QbSharedVideo.status }
  sharedAt:       { from: QbSharedVideo.sharedAt }
  approvedAt:     { from: QbSharedVideo.approvedAt }
  rejectReason:   { from: QbSharedVideo.rejectReason }
  withdrawnAt:    { from: QbSharedVideo.withdrawnAt }
  takenDownAt:    { from: QbSharedVideo.takenDownAt }
  takedownReason: { from: QbSharedVideo.takedownReason }
  revision:       { from: QbSharedVideo.revision }
  usesRunning:    { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  filters: [status]
  sorts:   [sharedAt]
  defaultSort: { field: sharedAt, asc: false }
  key: [sharedVideoId]
permissions: [qb.content.write]
---*/
-- The caller's own shares only (the me: dataset). The publications using one are counted from the cards (frozen when
-- they went on), those not ended: who uses it is not told.
SELECT
    v.{{QbSharedVideo.sharedVideoId}}  AS sharedVideoId,
    v.{{QbSharedVideo.assetId}}        AS assetId,
    CAST(v.{{QbSharedVideo.title}} AS text) AS title,
    v.{{QbSharedVideo.url}}            AS url,
    v.{{QbSharedVideo.durationSec}}    AS durationSec,
    v.{{QbSharedVideo.vertical}}       AS vertical,
    v.{{QbSharedVideo.topics}}         AS topics,
    v.{{QbSharedVideo.status}}         AS status,
    v.{{QbSharedVideo.sharedAt}}       AS sharedAt,
    v.{{QbSharedVideo.approvedAt}}     AS approvedAt,
    v.{{QbSharedVideo.rejectReason}}   AS rejectReason,
    v.{{QbSharedVideo.withdrawnAt}}    AS withdrawnAt,
    v.{{QbSharedVideo.takenDownAt}}    AS takenDownAt,
    v.{{QbSharedVideo.takedownReason}} AS takedownReason,
    v.{{QbSharedVideo.revision}}       AS revision,
    (SELECT count(*) FROM {{QbPublicationCard}} c
     WHERE c.{{QbPublicationCard.sharedVideoId}} = v.{{QbSharedVideo.sharedVideoId}}
       AND c.{{QbPublicationCard.status}} <> 'ENDED') AS usesRunning
FROM {{QbSharedVideo}} v
WHERE (CAST(:assetId AS text) IS NULL OR v.{{QbSharedVideo.assetId}} = CAST(:assetId AS text))
