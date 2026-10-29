/*---
id: qb.admin.broadcasts
description: >-
  Every announcement of the platform and every sponsor's notice (P-04), newest first - who sent it, to whom, its
  state and times, and once sent how many it reached, read, clicked through and then started the quiz
entities: [QbBroadcast, QbSponsor, QbPublicationCard, QbBroadcastReceipt, QbAttemptStart]
datasets:
  QbBroadcast: urn:jabiz:dataset:default:QbBroadcast
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbBroadcastReceipt: urn:jabiz:dataset:default:QbBroadcastReceipt
  QbAttemptStart: urn:jabiz:dataset:default:QbAttemptStart
params:
  senderKind:  { like: QbBroadcast.senderKind, description: "Only PLATFORM or only SPONSOR" }
  status:      { like: QbBroadcast.status, description: Only this state }
  audience:    { like: QbBroadcast.audience, description: Only this audience }
  broadcastId: { like: QbBroadcast.broadcastId, description: Only this one }
  q:           { kind: { type: text, maxLength: 100 }, description: "Only those whose title (in any language) or sponsor contains this" }
results:
  broadcastId:     { from: QbBroadcast.broadcastId }
  senderKind:      { from: QbBroadcast.senderKind }
  senderId:        { from: QbBroadcast.senderId }
  sponsorCompany:  { from: QbSponsor.company }
  audience:        { from: QbBroadcast.audience }
  type:            { from: QbBroadcast.type }
  status:          { from: QbBroadcast.status }
  title:           { from: QbBroadcast.title }
  body:            { from: QbBroadcast.body }
  targetMode:      { from: QbBroadcast.targetMode }
  regions:         { from: QbBroadcast.regions }
  targetCount:     { from: QbBroadcast.targetCount }
  publicationId:   { from: QbBroadcast.publicationId }
  publicationName: { from: QbPublicationCard.name }
  scheduledAt:     { from: QbBroadcast.scheduledAt }
  sentAt:          { from: QbBroadcast.sentAt }
  expiresAt:       { from: QbBroadcast.expiresAt }
  audienceCount:   { from: QbBroadcast.audienceCount }
  reads:           { kind: { type: numeric, precision: 9, scale: 0 } }
  clicks:          { kind: { type: numeric, precision: 9, scale: 0 } }
  conversions:     { kind: { type: numeric, precision: 9, scale: 0 } }
  cancelReason:    { from: QbBroadcast.cancelReason }
  withdrawnAt:     { from: QbBroadcast.withdrawnAt }
  withdrawnBy:     { from: QbBroadcast.withdrawnBy }
  withdrawReason:  { from: QbBroadcast.withdrawReason }
  revision:        { from: QbBroadcast.revision }
  editedAt:        { from: QbBroadcast.editedAt }
list:
  sorts: [editedAt, sentAt, status, title]
  defaultSort: { field: editedAt, asc: false }
  key: [broadcastId]
permissions: [qb.admin.content.write]
---*/
-- The statistics of qb.sponsor.broadcasts (Q7-14), exact: counts of people, never who. A discarded draft is left out.
-- The text is found as written: strpos takes it literally.
SELECT
    b.{{QbBroadcast.broadcastId}}   AS broadcastId,
    b.{{QbBroadcast.senderKind}}    AS senderKind,
    b.{{QbBroadcast.senderId}}      AS senderId,
    sp.{{QbSponsor.company}}        AS sponsorCompany,
    b.{{QbBroadcast.audience}}      AS audience,
    b.{{QbBroadcast.type}}          AS type,
    b.{{QbBroadcast.status}}        AS status,
    b.{{QbBroadcast.title}}         AS title,
    b.{{QbBroadcast.body}}          AS body,
    b.{{QbBroadcast.targetMode}}    AS targetMode,
    b.{{QbBroadcast.regions}}       AS regions,
    b.{{QbBroadcast.targetCount}}   AS targetCount,
    b.{{QbBroadcast.publicationId}} AS publicationId,
    c.{{QbPublicationCard.name}}    AS publicationName,
    b.{{QbBroadcast.scheduledAt}}   AS scheduledAt,
    b.{{QbBroadcast.sentAt}}        AS sentAt,
    b.{{QbBroadcast.expiresAt}}     AS expiresAt,
    b.{{QbBroadcast.audienceCount}} AS audienceCount,
    s.reads                         AS reads,
    s.clicks                        AS clicks,
    s.conversions                   AS conversions,
    b.{{QbBroadcast.cancelReason}}  AS cancelReason,
    b.{{QbBroadcast.withdrawnAt}}   AS withdrawnAt,
    b.{{QbBroadcast.withdrawnBy}}   AS withdrawnBy,
    b.{{QbBroadcast.withdrawReason}} AS withdrawReason,
    b.{{QbBroadcast.revision}}      AS revision,
    b.{{QbBroadcast.editedAt}}      AS editedAt
FROM {{QbBroadcast}} b
LEFT JOIN {{QbSponsor}} sp ON b.{{QbBroadcast.senderKind}} = 'SPONSOR'
    AND sp.{{QbSponsor.userId}} = b.{{QbBroadcast.senderId}}
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
  AND (CAST(:senderKind AS text) IS NULL OR b.{{QbBroadcast.senderKind}} = CAST(:senderKind AS text))
  AND (CAST(:status AS text) IS NULL OR b.{{QbBroadcast.status}} = CAST(:status AS text))
  AND (CAST(:audience AS text) IS NULL OR b.{{QbBroadcast.audience}} = CAST(:audience AS text))
  AND (CAST(:broadcastId AS uuid) IS NULL OR b.{{QbBroadcast.broadcastId}} = CAST(:broadcastId AS uuid))
  AND (CAST(:q AS text) IS NULL
       OR EXISTS (SELECT 1 FROM jsonb_each_text(b.{{QbBroadcast.title}}) e
                  WHERE strpos(lower(e.value), lower(CAST(:q AS text))) > 0)
       OR strpos(lower(coalesce(sp.{{QbSponsor.company}}, '')), lower(CAST(:q AS text))) > 0)
