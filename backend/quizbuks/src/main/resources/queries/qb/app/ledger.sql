/*---
id: qb.app.ledger
description: The taker's transactions (U-51, U-52), newest first - rewards paid and pending (transfers follow in Q6b)
entities: [QbAttempt, QbPublicationCard]
datasets:
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
params:
  q: { kind: { type: text, maxLength: 100 }, description: Only transactions whose publication's name or quiz title contains this text }
results:
  entryId:       { kind: { type: text, maxLength: 64 } }
  type:          { kind: { type: text, maxLength: 20 } }
  status:        { kind: { type: text, maxLength: 20 } }
  amount:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  occurredAt:    { kind: { type: temporal, role: EVENT_TIME } }
  publicationId: { from: QbAttempt.publicationId }
  name:          { from: QbPublicationCard.name }
  quizTitle:     { from: QbPublicationCard.quizTitle }
list:
  filters: [type, status, occurredAt, amount]
  sorts:   [occurredAt, amount]
  defaultSort: { field: occurredAt, asc: false }
  key: [entryId]
permissions: [qb.me]
---*/
-- A reward is COMPLETED once paid (what the taker received: the reward less the commission) and PROCESSING while it
-- waits for its sponsor (the reward before the commission). The text is found as written: strpos takes it literally.
SELECT
    CAST(m.{{QbAttempt.attemptId}} AS varchar(64)) AS entryId,
    CAST('REWARD' AS varchar(20))                 AS type,
    CAST(CASE WHEN m.{{QbAttempt.rewardStatus}} = 'PAID' THEN 'COMPLETED' ELSE 'PROCESSING' END AS varchar(20))
                                                  AS status,
    CASE WHEN m.{{QbAttempt.rewardStatus}} = 'PAID'
         THEN m.{{QbAttempt.reward}} - coalesce(m.{{QbAttempt.fee}}, 0)
         ELSE m.{{QbAttempt.reward}} END          AS amount,
    coalesce(m.{{QbAttempt.rewardPaidAt}}, m.{{QbAttempt.submittedAt}}) AS occurredAt,
    m.{{QbAttempt.publicationId}}                 AS publicationId,
    c.{{QbPublicationCard.name}}                  AS name,
    c.{{QbPublicationCard.quizTitle}}             AS quizTitle
FROM {{QbAttempt}} m
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = m.{{QbAttempt.publicationId}}
WHERE m.{{QbAttempt.rewardStatus}} IN ('PAID', 'PENDING')
  AND m.{{QbAttempt.reward}} > 0
  AND (CAST(:q AS text) IS NULL
       OR strpos(lower(c.{{QbPublicationCard.name}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(c.{{QbPublicationCard.quizTitle}}), lower(CAST(:q AS text))) > 0)
