/*---
id: qb.sponsor.transactions
description: The sponsor's transactions (M-50 - M-52), newest first - the rewards their publications paid or owe, and their top-ups
entities: [QbAttempt, QbPublication, QbProfile, QbTopUp]
datasets:
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbProfile: urn:jabiz:dataset:default:QbProfile
  QbTopUp: urn:jabiz:dataset:sponsor:QbTopUp
params:
  type:          { kind: { type: text, maxLength: 20 }, description: "Only REWARD or only TOPUP rows" }
  status:        { kind: { type: text, maxLength: 20 }, description: "Only COMPLETED, PROCESSING or FAILED rows" }
  publicationId: { like: QbPublication.publicationId, description: Only the rewards of this publication }
  userKey:       { kind: { type: text, maxLength: 32 }, description: "Only the rewards of the taker with this key (the rows' userKey)" }
  from:          { kind: { type: temporal, role: EVENT_TIME }, description: Only rows that occurred at or after this time }
  to:            { kind: { type: temporal, role: EVENT_TIME }, description: Only rows that occurred before this time }
  q:             { kind: { type: text, maxLength: 100 }, description: "Only rewards whose publication's name or taker's name contains this text" }
results:
  entryId:         { kind: { type: text, maxLength: 64 } }
  type:            { kind: { type: text, maxLength: 20 } }
  status:          { kind: { type: text, maxLength: 20 } }
  amount:          { kind: { type: monetary, currency: JPY, scale: 0 } }
  refunded:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  occurredAt:      { kind: { type: temporal, role: EVENT_TIME } }
  publicationId:   { from: QbPublication.publicationId }
  publicationName: { from: QbPublication.name }
  userKey:         { kind: { type: text, maxLength: 32 } }
  userName:        { from: QbProfile.displayName }
  score:           { from: QbAttempt.score }
  fullScore:       { from: QbAttempt.fullScore }
list:
  sorts:   [occurredAt, amount]
  defaultSort: { field: occurredAt, asc: false }
  key: [entryId]
permissions: [qb.sponsor.finance.read]
---*/
-- Only the caller's own: rewards through the sponsor's publications (scoped to the caller), top-ups through the
-- sponsor's top-ups (scoped likewise). A reward is a finished attempt that earned something (one that earned nothing
-- is no transaction): COMPLETED once paid (the whole reward left the wallet), PROCESSING while it waits for a top-up.
-- A top-up is COMPLETED once paid, PROCESSING while its checkout is opening or open, FAILED when it expired or failed
-- (nothing was charged); refunded is what was refunded or taken back by a lost dispute. The taker is shown by the
-- name of their profile only, never their address; neither the answers nor the result are read. "Only this user"
-- filters by userKey, md5(sponsor : user): not the user id, and the same taker gets a different key with each
-- sponsor; it is no secret (anyone who knows both ids can compute it). Every filter is a
-- parameter, so that the list, the summary (qb.sponsor.transaction-summary, the same rows) and the export agree; the
-- text is found as written: strpos takes it literally.
SELECT x.*
FROM (
    SELECT
        CAST(m.{{QbAttempt.attemptId}} AS varchar(64))  AS entryId,
        CAST('REWARD' AS varchar(20))                  AS type,
        CAST(CASE WHEN m.{{QbAttempt.rewardStatus}} = 'PAID' THEN 'COMPLETED' ELSE 'PROCESSING' END AS varchar(20))
                                                       AS status,
        m.{{QbAttempt.reward}}                         AS amount,
        CAST(NULL AS numeric)                          AS refunded,
        CASE WHEN m.{{QbAttempt.rewardStatus}} = 'PAID'
             THEN m.{{QbAttempt.rewardPaidAt}} ELSE m.{{QbAttempt.submittedAt}} END AS occurredAt,
        p.{{QbPublication.publicationId}}              AS publicationId,
        p.{{QbPublication.name}}                       AS publicationName,
        CAST(md5(p.{{QbPublication.publisherId}} || ':' || m.{{QbAttempt.userId}}) AS varchar(32)) AS userKey,
        u.{{QbProfile.displayName}}                    AS userName,
        m.{{QbAttempt.score}}                          AS score,
        m.{{QbAttempt.fullScore}}                      AS fullScore
    FROM {{QbAttempt}} m
    JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = m.{{QbAttempt.publicationId}}
    LEFT JOIN {{QbProfile}} u ON u.{{QbProfile.userId}} = m.{{QbAttempt.userId}}
    WHERE m.{{QbAttempt.submittedAt}} IS NOT NULL
      AND m.{{QbAttempt.rewardStatus}} IN ('PAID', 'PENDING')
      AND m.{{QbAttempt.reward}} > 0
    UNION ALL
    SELECT
        CAST(t.{{QbTopUp.topUpId}} AS varchar(64))     AS entryId,
        CAST('TOPUP' AS varchar(20))                   AS type,
        CAST(CASE WHEN t.{{QbTopUp.status}} = 'PAID' THEN 'COMPLETED'
                  WHEN t.{{QbTopUp.status}} IN ('FAILED', 'EXPIRED', 'CANCELLED') THEN 'FAILED'
                  ELSE 'PROCESSING' END AS varchar(20)) AS status,
        t.{{QbTopUp.amount}}                           AS amount,
        t.{{QbTopUp.refundedAmount}} + t.{{QbTopUp.disputeClawback}} AS refunded,
        coalesce(t.{{QbTopUp.paidAt}}, t.{{QbTopUp.createdAt}}) AS occurredAt,
        CAST(NULL AS uuid)                             AS publicationId,
        CAST(NULL AS varchar(100))                     AS publicationName,
        CAST(NULL AS varchar(32))                      AS userKey,
        CAST(NULL AS varchar(100))                     AS userName,
        CAST(NULL AS numeric)                          AS score,
        CAST(NULL AS numeric)                          AS fullScore
    FROM {{QbTopUp}} t
) x
WHERE (CAST(:type AS text) IS NULL OR x.type = CAST(:type AS text))
  AND (CAST(:status AS text) IS NULL OR x.status = CAST(:status AS text))
  AND (CAST(:publicationId AS uuid) IS NULL OR x.publicationId = CAST(:publicationId AS uuid))
  AND (CAST(:userKey AS text) IS NULL OR x.userKey = CAST(:userKey AS text))
  AND (CAST(:from AS timestamptz) IS NULL OR x.occurredAt >= CAST(:from AS timestamptz))
  AND (CAST(:to AS timestamptz) IS NULL OR x.occurredAt < CAST(:to AS timestamptz))
  AND (CAST(:q AS text) IS NULL
       OR strpos(lower(x.publicationName), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(x.userName, '')), lower(CAST(:q AS text))) > 0)
