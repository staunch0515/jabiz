/*---
id: qb.sponsor.transaction-summary
description: The totals of the sponsor's transactions (M-51) - how many, then the rewards (paid out) and the top-ups (paid in) apart, by state
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
  totalCount:             { kind: { type: numeric, precision: 12, scale: 0 } }
  rewardCompletedCount:   { kind: { type: numeric, precision: 12, scale: 0 } }
  rewardCompletedAmount:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  rewardProcessingCount:  { kind: { type: numeric, precision: 12, scale: 0 } }
  rewardProcessingAmount: { kind: { type: monetary, currency: JPY, scale: 0 } }
  topUpCompletedCount:    { kind: { type: numeric, precision: 12, scale: 0 } }
  topUpCompletedAmount:   { kind: { type: monetary, currency: JPY, scale: 0 } }
  topUpRefundedAmount:    { kind: { type: monetary, currency: JPY, scale: 0 } }
  topUpProcessingCount:   { kind: { type: numeric, precision: 12, scale: 0 } }
  topUpProcessingAmount:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  topUpFailedCount:       { kind: { type: numeric, precision: 12, scale: 0 } }
  topUpFailedAmount:      { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.sponsor.finance.read]
---*/
-- One row over exactly the rows of qb.sponsor.transactions with the same parameters: the inner query and the
-- conditions are that template's, copied (a template cannot include another); SponsorTransactionsIT checks that the
-- totals equal the sums of the list, type by type. Rewards (money out) and top-ups (money in) are never added
-- together: only the number of rows is a total. A completed top-up counts net of what was refunded or taken back by a
-- dispute, which is given apart; a failed top-up charged nothing (its amount is what was asked).
SELECT
    count(*)                                                                         AS totalCount,
    count(*) FILTER (WHERE x.type = 'REWARD' AND x.status = 'COMPLETED')             AS rewardCompletedCount,
    coalesce(sum(x.amount) FILTER (WHERE x.type = 'REWARD' AND x.status = 'COMPLETED'), 0)
                                                                                     AS rewardCompletedAmount,
    count(*) FILTER (WHERE x.type = 'REWARD' AND x.status = 'PROCESSING')            AS rewardProcessingCount,
    coalesce(sum(x.amount) FILTER (WHERE x.type = 'REWARD' AND x.status = 'PROCESSING'), 0)
                                                                                     AS rewardProcessingAmount,
    count(*) FILTER (WHERE x.type = 'TOPUP' AND x.status = 'COMPLETED')              AS topUpCompletedCount,
    coalesce(sum(x.amount - x.refunded) FILTER (WHERE x.type = 'TOPUP' AND x.status = 'COMPLETED'), 0)
                                                                                     AS topUpCompletedAmount,
    coalesce(sum(x.refunded) FILTER (WHERE x.type = 'TOPUP' AND x.status = 'COMPLETED'), 0)
                                                                                     AS topUpRefundedAmount,
    count(*) FILTER (WHERE x.type = 'TOPUP' AND x.status = 'PROCESSING')             AS topUpProcessingCount,
    coalesce(sum(x.amount) FILTER (WHERE x.type = 'TOPUP' AND x.status = 'PROCESSING'), 0)
                                                                                     AS topUpProcessingAmount,
    count(*) FILTER (WHERE x.type = 'TOPUP' AND x.status = 'FAILED')                 AS topUpFailedCount,
    coalesce(sum(x.amount) FILTER (WHERE x.type = 'TOPUP' AND x.status = 'FAILED'), 0)
                                                                                     AS topUpFailedAmount
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
