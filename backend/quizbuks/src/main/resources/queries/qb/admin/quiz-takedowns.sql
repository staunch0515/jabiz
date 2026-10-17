/*---
id: qb.admin.quiz-takedowns
description: The publications of a quiz an administrator took down (paused, still so, or ended), with the reasons, for the review of another publication of it
entities: [QbPublication]
datasets:
  QbPublication: urn:jabiz:dataset:default:QbPublication
params:
  sponsorId: { like: QbPublication.sponsorId, required: true, description: The quiz's owner }
  quizId:    { like: QbPublication.quizId, required: true, description: The quiz }
results:
  publicationId: { from: QbPublication.publicationId }
  name:          { from: QbPublication.name }
  status:        { from: QbPublication.status }
  pausedBy:      { from: QbPublication.pausedBy }
  pausedAt:      { from: QbPublication.pausedAt }
  endReason:     { from: QbPublication.endReason }
  endedAt:       { from: QbPublication.endedAt }
  adminReason:   { from: QbPublication.adminReason }
permissions: [qb.admin.publication.read]
---*/
-- Q10b, review M-d: a publication of a quiz like this goes on only after a person's review, and the reviewer reads why
-- the others were taken down (PublicationAdmin.takenDown is the same condition). By the immutable sponsor (indexed),
-- then the quiz.
SELECT
    p.{{QbPublication.publicationId}} AS publicationId,
    p.{{QbPublication.name}}          AS name,
    p.{{QbPublication.status}}        AS status,
    p.{{QbPublication.pausedBy}}      AS pausedBy,
    p.{{QbPublication.pausedAt}}      AS pausedAt,
    p.{{QbPublication.endReason}}     AS endReason,
    p.{{QbPublication.endedAt}}       AS endedAt,
    p.{{QbPublication.adminReason}}   AS adminReason
FROM {{QbPublication}} p
WHERE p.{{QbPublication.sponsorId}} = :sponsorId
  AND p.{{QbPublication.quizId}} = :quizId
  AND ((p.{{QbPublication.status}} = 'PAUSED' AND p.{{QbPublication.pausedBy}} = 'ADMIN')
       OR p.{{QbPublication.endReason}} = 'ADMIN')
