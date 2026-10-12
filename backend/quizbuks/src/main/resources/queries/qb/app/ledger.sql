/*---
id: qb.app.ledger
description: The taker's transactions (U-51, U-52), newest first - rewards paid and pending, and transfers to their Stripe account
entities: [QbAttempt, QbPublicationCard, QbTransfer]
datasets:
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbTransfer: urn:jabiz:dataset:taker:QbTransfer
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
-- waits for its sponsor (the reward before the commission). A transfer (Q6b) is PROCESSING until Stripe made it
-- (review, sending, in doubt), COMPLETED once sent, FAILED when it failed or was rejected (the money is back on the
-- balance) and REVERSED when Stripe reversed it; its amount is what left the balance. A text search finds rewards
-- only (transfers have no publication). The text is found as written: strpos takes it literally.
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
UNION ALL
SELECT
    CAST(t.{{QbTransfer.transferId}} AS varchar(64)) AS entryId,
    CAST('TRANSFER' AS varchar(20))                 AS type,
    CAST(CASE WHEN t.{{QbTransfer.status}} = 'SENT' THEN 'COMPLETED'
              WHEN t.{{QbTransfer.status}} IN ('FAILED', 'REJECTED') THEN 'FAILED'
              WHEN t.{{QbTransfer.status}} = 'REVERSED' THEN 'REVERSED'
              ELSE 'PROCESSING' END AS varchar(20)) AS status,
    t.{{QbTransfer.amount}}                         AS amount,
    coalesce(t.{{QbTransfer.sentAt}}, t.{{QbTransfer.createdAt}}) AS occurredAt,
    CAST(NULL AS uuid)                              AS publicationId,
    CAST(NULL AS varchar(100))                      AS name,
    CAST(NULL AS varchar(100))                      AS quizTitle
FROM {{QbTransfer}} t
WHERE CAST(:q AS text) IS NULL
