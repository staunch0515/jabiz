/*---
id: qb.admin.attempt-rewards
description: The money of every submitted attempt - reward, commission and their ledger transaction (P-01, finance only), newest first
entities: [QbAttempt, QbPublicationCard]
datasets:
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
results:
  attemptId:           { from: QbAttempt.attemptId }
  userId:              { from: QbAttempt.userId }
  publicationId:       { from: QbAttempt.publicationId }
  name:                { from: QbPublicationCard.name }
  sponsorId:           { from: QbAttempt.sponsorId }
  submittedAt:         { from: QbAttempt.submittedAt }
  reward:              { from: QbAttempt.reward }
  fee:                 { from: QbAttempt.fee }
  rewardStatus:        { from: QbAttempt.rewardStatus }
  rewardPaidAt:        { from: QbAttempt.rewardPaidAt }
  ledgerTransactionId: { from: QbAttempt.ledgerTransactionId }
list:
  filters: [userId, publicationId, sponsorId, rewardStatus, submittedAt]
  sorts:   [submittedAt, reward]
  defaultSort: { field: submittedAt, asc: false }
  key: [attemptId]
permissions: [qb.admin.finance.read]
---*/
-- The amounts qb.admin.attempts (support, qb.admin.users.read) leaves out: money is the finance administrators' (Q9a).
-- Attempts not yet submitted have no reward and are left out.
SELECT
    m.{{QbAttempt.attemptId}}           AS attemptId,
    m.{{QbAttempt.userId}}              AS userId,
    m.{{QbAttempt.publicationId}}       AS publicationId,
    c.{{QbPublicationCard.name}}        AS name,
    m.{{QbAttempt.sponsorId}}           AS sponsorId,
    m.{{QbAttempt.submittedAt}}         AS submittedAt,
    m.{{QbAttempt.reward}}              AS reward,
    m.{{QbAttempt.fee}}                 AS fee,
    m.{{QbAttempt.rewardStatus}}        AS rewardStatus,
    m.{{QbAttempt.rewardPaidAt}}        AS rewardPaidAt,
    m.{{QbAttempt.ledgerTransactionId}} AS ledgerTransactionId
FROM {{QbAttempt}} m
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = m.{{QbAttempt.publicationId}}
WHERE m.{{QbAttempt.submittedAt}} IS NOT NULL
