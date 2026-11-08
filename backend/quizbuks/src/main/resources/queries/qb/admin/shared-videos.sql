/*---
id: qb.admin.shared-videos
description: >-
  F-S12: the videos sponsors share, newest first: what was shared, its review, withdrawal or takedown, and how many
  publications use it now. Headquarters reads every provider's, an operator its own sponsors' (the publications counted
  are those the session reads: an operator's, its own sponsors')
entities: [QbSharedVideo, QbSponsor, QbSponsorOperator, QbOperator, QbPublicationCard]
datasets:
  QbSharedVideo: urn:jabiz:dataset:default:QbSharedVideo
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperator: urn:jabiz:dataset:default:QbOperator
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
params:
  q: { kind: { type: text, maxLength: 100 }, description: "Only videos whose title (any language) or provider contains this text" }
results:
  sharedVideoId:      { from: QbSharedVideo.sharedVideoId }
  providerId:         { from: QbSharedVideo.providerId }
  company:            { from: QbSponsor.company }
  operatorId:         { from: QbSponsorOperator.operatorId }
  operatorName:       { from: QbOperator.name }
  title:              { kind: { type: text } }
  url:                { from: QbSharedVideo.url }
  durationSec:        { from: QbSharedVideo.durationSec }
  vertical:           { from: QbSharedVideo.vertical }
  topics:             { from: QbSharedVideo.topics }
  status:             { from: QbSharedVideo.status }
  approvalRequestId:  { from: QbSharedVideo.approvalRequestId }
  sharedAt:           { from: QbSharedVideo.sharedAt }
  approvedAt:         { from: QbSharedVideo.approvedAt }
  rejectReason:       { from: QbSharedVideo.rejectReason }
  withdrawnAt:        { from: QbSharedVideo.withdrawnAt }
  takenDownAt:        { from: QbSharedVideo.takenDownAt }
  takedownReason:     { from: QbSharedVideo.takedownReason }
  takedownOperatorId: { from: QbSharedVideo.takedownOperatorId }
  usesRunning:        { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  filters: [sharedVideoId, providerId, status, vertical, operatorId]
  sorts:   [sharedAt, approvedAt]
  defaultSort: { field: sharedAt, asc: false }
  key: [sharedVideoId]
permissions: [qb.admin.shared-video.read]
---*/
-- For an operator's session the videos are its own sponsors' only (Q59c-2), and so are the onboarding, the assignment
-- and the cards it reads. The company is the provider's onboarding's current one.
SELECT * FROM (
    SELECT
        v.{{QbSharedVideo.sharedVideoId}}      AS sharedVideoId,
        v.{{QbSharedVideo.providerId}}         AS providerId,
        (SELECT max(p.{{QbSponsor.company}}) FROM {{QbSponsor}} p
         WHERE p.{{QbSponsor.userId}} = v.{{QbSharedVideo.providerId}}) AS company,
        qso.{{QbSponsorOperator.operatorId}}   AS operatorId,
        qop.{{QbOperator.name}}                AS operatorName,
        CAST(v.{{QbSharedVideo.title}} AS text) AS title,
        v.{{QbSharedVideo.url}}                AS url,
        v.{{QbSharedVideo.durationSec}}        AS durationSec,
        v.{{QbSharedVideo.vertical}}           AS vertical,
        v.{{QbSharedVideo.topics}}             AS topics,
        v.{{QbSharedVideo.status}}             AS status,
        v.{{QbSharedVideo.approvalRequestId}}  AS approvalRequestId,
        v.{{QbSharedVideo.sharedAt}}           AS sharedAt,
        v.{{QbSharedVideo.approvedAt}}         AS approvedAt,
        v.{{QbSharedVideo.rejectReason}}       AS rejectReason,
        v.{{QbSharedVideo.withdrawnAt}}        AS withdrawnAt,
        v.{{QbSharedVideo.takenDownAt}}        AS takenDownAt,
        v.{{QbSharedVideo.takedownReason}}     AS takedownReason,
        v.{{QbSharedVideo.takedownOperatorId}} AS takedownOperatorId,
        (SELECT count(*) FROM {{QbPublicationCard}} c
         WHERE c.{{QbPublicationCard.sharedVideoId}} = v.{{QbSharedVideo.sharedVideoId}}
           AND c.{{QbPublicationCard.status}} <> 'ENDED') AS usesRunning
    FROM {{QbSharedVideo}} v
    LEFT JOIN {{QbSponsorOperator}} qso ON qso.{{QbSponsorOperator.userId}} = v.{{QbSharedVideo.providerId}}
    LEFT JOIN {{QbOperator}} qop ON qop.{{QbOperator.operatorId}} = qso.{{QbSponsorOperator.operatorId}}
) x
WHERE (CAST(:q AS text) IS NULL OR strpos(lower(x.title), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(x.company, '')), lower(CAST(:q AS text))) > 0)
