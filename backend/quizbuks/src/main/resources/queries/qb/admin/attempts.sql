/*---
id: qb.admin.attempts
description: Every attempt with its publication, result and reward state (P-01 answering history, support), newest first; the amounts are in qb.admin.attempt-rewards (finance)
entities: [QbAttempt, QbPublicationCard]
datasets:
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
results:
  attemptId:           { from: QbAttempt.attemptId }
  userId:              { from: QbAttempt.userId }
  publicationId:       { from: QbAttempt.publicationId }
  name:                { from: QbPublicationCard.name }
  publisherKind:       { from: QbAttempt.publisherKind }
  publisherId:         { from: QbAttempt.publisherId }
  status:              { from: QbAttempt.status }
  startedAt:           { from: QbAttempt.startedAt }
  deadline:            { from: QbAttempt.deadline }
  submittedAt:         { from: QbAttempt.submittedAt }
  score:               { from: QbAttempt.score }
  fullScore:           { from: QbAttempt.fullScore }
  passed:              { from: QbAttempt.passed }
  rewardStatus:        { from: QbAttempt.rewardStatus }
  rewardPaidAt:        { from: QbAttempt.rewardPaidAt }
  watchedSec:          { from: QbAttempt.watchedSec }
  videoEnded:          { from: QbAttempt.videoEnded }
  playbackStatus:      { from: QbAttempt.playbackStatus }
list:
  filters: [userId, publicationId, publisherKind, publisherId, status, rewardStatus, startedAt, submittedAt]
  sorts:   [startedAt, submittedAt, status, score]
  defaultSort: { field: startedAt, asc: false }
  key: [attemptId]
permissions: [qb.admin.users.read]
---*/
-- Neither the answers nor the result: support needs the outcome, not what a taker chose.
SELECT
    m.{{QbAttempt.attemptId}}           AS attemptId,
    m.{{QbAttempt.userId}}              AS userId,
    m.{{QbAttempt.publicationId}}       AS publicationId,
    c.{{QbPublicationCard.name}}        AS name,
    m.{{QbAttempt.publisherKind}}         AS publisherKind,
    m.{{QbAttempt.publisherId}}           AS publisherId,
    m.{{QbAttempt.status}}              AS status,
    m.{{QbAttempt.startedAt}}           AS startedAt,
    m.{{QbAttempt.deadline}}            AS deadline,
    m.{{QbAttempt.submittedAt}}         AS submittedAt,
    m.{{QbAttempt.score}}               AS score,
    m.{{QbAttempt.fullScore}}           AS fullScore,
    m.{{QbAttempt.passed}}              AS passed,
    m.{{QbAttempt.rewardStatus}}        AS rewardStatus,
    m.{{QbAttempt.rewardPaidAt}}        AS rewardPaidAt,
    -- Q75 (D-Q75-9): what the App reported of the video's playback at the start.
    m.{{QbAttempt.watchedSec}}          AS watchedSec,
    m.{{QbAttempt.videoEnded}}          AS videoEnded,
    m.{{QbAttempt.playbackStatus}}      AS playbackStatus
FROM {{QbAttempt}} m
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = m.{{QbAttempt.publicationId}}
