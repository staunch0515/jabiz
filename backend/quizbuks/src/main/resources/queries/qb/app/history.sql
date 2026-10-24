/*---
id: qb.app.history
description: The taker's own attempts (U-40 – U-42), newest first, with their results and rewards
entities: [QbAttempt, QbPublicationCard, QbCashReward, QbRedemption]
datasets:
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbCashReward: urn:jabiz:dataset:taker:QbCashReward
  QbRedemption: urn:jabiz:dataset:taker:QbRedemption
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
  rewardMethod: { from: QbPublicationCard.rewardMethod }
  cashRewardId: { from: QbCashReward.cashRewardId }
  cashStatus:   { from: QbCashReward.status }
  cashCode:     { from: QbCashReward.code }
  cashConfirmBy: { from: QbCashReward.confirmBy }
  redeemStatus: { from: QbRedemption.status }
  redeemExpiresAt: { from: QbRedemption.expiresAt }
  startedAt:    { from: QbAttempt.startedAt }
  submittedAt:  { from: QbAttempt.submittedAt }
  deadline:     { from: QbAttempt.deadline }
list:
  filters: [status, category, rewardStatus, rewardMethod, cashStatus, startedAt, submittedAt, reward, passed]
  sorts:   [startedAt, submittedAt, reward]
  defaultSort: { field: startedAt, asc: false }
  key: [attemptId]
permissions: [qb.me]
---*/
-- The categories of the history (D-Q5-11): PASSED and FAILED once finished, IN_PROGRESS while it can still be
-- continued, TIMED_OUT once its deadline passed and it waits to be finished with its saved answers. The text is found
-- as written: strpos takes it literally, so % _ and \ match themselves. A cash reward or a prize (Q22) is the
-- taker's own record of the attempt's reward, one each at most.
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
    c.{{QbPublicationCard.rewardMethod}}   AS rewardMethod,
    cr.{{QbCashReward.cashRewardId}}       AS cashRewardId,
    cr.{{QbCashReward.status}}             AS cashStatus,
    cr.{{QbCashReward.code}}               AS cashCode,
    cr.{{QbCashReward.confirmBy}}          AS cashConfirmBy,
    rd.{{QbRedemption.status}}             AS redeemStatus,
    rd.{{QbRedemption.expiresAt}}          AS redeemExpiresAt,
    m.{{QbAttempt.startedAt}}              AS startedAt,
    m.{{QbAttempt.submittedAt}}            AS submittedAt,
    m.{{QbAttempt.deadline}}               AS deadline
FROM {{QbAttempt}} m
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = m.{{QbAttempt.publicationId}}
LEFT JOIN {{QbCashReward}} cr ON cr.{{QbCashReward.attemptId}} = m.{{QbAttempt.attemptId}}
LEFT JOIN {{QbRedemption}} rd ON rd.{{QbRedemption.attemptId}} = m.{{QbAttempt.attemptId}}
WHERE (CAST(:q AS text) IS NULL
       OR strpos(lower(c.{{QbPublicationCard.name}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(c.{{QbPublicationCard.quizTitle}}), lower(CAST(:q AS text))) > 0)
