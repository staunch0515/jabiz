/*---
id: qb.app.history
description: The taker's own attempts (U-40 – U-42), newest first, with their results and rewards
entities: [QbAttempt, QbPublicationCard]
datasets:
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, description: "The client's now: an attempt in progress past its deadline is waiting to be finished" }
  q:  { kind: { type: text, maxLength: 100 }, description: Only attempts whose publication's name or quiz title contains this text }
results:
  attemptId:    { from: QbAttempt.attemptId }
  publicationId: { from: QbAttempt.publicationId }
  name:         { from: QbPublicationCard.name }
  quizTitle:    { from: QbPublicationCard.quizTitle }
  cover:        { from: QbPublicationCard.cover }
  kind:         { from: QbPublicationCard.kind }
  status:       { from: QbAttempt.status }
  category:     { kind: { type: text, maxLength: 20 } }
  passed:       { from: QbAttempt.passed }
  score:        { from: QbAttempt.score }
  fullScore:    { from: QbPublicationCard.fullScore }
  reward:       { from: QbAttempt.reward }
  rewardStatus: { from: QbAttempt.rewardStatus }
  startedAt:    { from: QbAttempt.startedAt }
  submittedAt:  { from: QbAttempt.submittedAt }
  deadline:     { from: QbAttempt.deadline }
list:
  filters: [status, category, rewardStatus, startedAt, submittedAt, reward, passed]
  sorts:   [startedAt, submittedAt, reward]
  defaultSort: { field: startedAt, asc: false }
  key: [attemptId]
permissions: [qb.me]
---*/
-- The categories of the history (D-Q5-11): PASSED and FAILED once finished, IN_PROGRESS while it can still be
-- continued, TIMED_OUT once its deadline passed and it waits to be finished with its saved answers. The text is found
-- as written: strpos takes it literally, so % _ and \ match themselves.
SELECT
    m.{{QbAttempt.attemptId}}              AS attemptId,
    m.{{QbAttempt.publicationId}}          AS publicationId,
    c.{{QbPublicationCard.name}}           AS name,
    c.{{QbPublicationCard.quizTitle}}      AS quizTitle,
    c.{{QbPublicationCard.cover}}          AS cover,
    c.{{QbPublicationCard.kind}}           AS kind,
    m.{{QbAttempt.status}}                 AS status,
    CAST(CASE
        WHEN m.{{QbAttempt.status}} <> 'IN_PROGRESS' AND m.{{QbAttempt.passed}} THEN 'PASSED'
        WHEN m.{{QbAttempt.status}} <> 'IN_PROGRESS' THEN 'FAILED'
        WHEN CAST(:at AS timestamptz) IS NOT NULL AND m.{{QbAttempt.deadline}} < CAST(:at AS timestamptz)
            THEN 'TIMED_OUT'
        ELSE 'IN_PROGRESS' END AS varchar(20)) AS category,
    m.{{QbAttempt.passed}}                 AS passed,
    m.{{QbAttempt.score}}                  AS score,
    c.{{QbPublicationCard.fullScore}}      AS fullScore,
    m.{{QbAttempt.reward}}                 AS reward,
    m.{{QbAttempt.rewardStatus}}           AS rewardStatus,
    m.{{QbAttempt.startedAt}}              AS startedAt,
    m.{{QbAttempt.submittedAt}}            AS submittedAt,
    m.{{QbAttempt.deadline}}               AS deadline
FROM {{QbAttempt}} m
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = m.{{QbAttempt.publicationId}}
WHERE (CAST(:q AS text) IS NULL
       OR strpos(lower(c.{{QbPublicationCard.name}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(c.{{QbPublicationCard.quizTitle}}), lower(CAST(:q AS text))) > 0)
