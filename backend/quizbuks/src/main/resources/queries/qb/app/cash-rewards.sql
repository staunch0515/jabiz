/*---
id: qb.app.cash-rewards
description: "The taker's own cash rewards (Q22): what the sponsor owes or paid them, newest first"
entities: [QbCashReward, QbPublicationCard, QbSponsor]
datasets:
  QbCashReward: urn:jabiz:dataset:taker:QbCashReward
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  open: { kind: { type: bool }, description: Only rewards that still need something of the taker or the sponsor }
results:
  cashRewardId:    { from: QbCashReward.cashRewardId }
  attemptId:       { from: QbCashReward.attemptId }
  code:            { from: QbCashReward.code }
  publicationId:   { from: QbCashReward.publicationId }
  publicationName: { from: QbPublicationCard.name }
  sponsorCompany:  { from: QbSponsor.company }
  amount:          { from: QbCashReward.amount }
  status:          { from: QbCashReward.status }
  dueAt:           { from: QbCashReward.dueAt }
  markedPaidAt:    { from: QbCashReward.markedPaidAt }
  confirmBy:       { from: QbCashReward.confirmBy }
  confirmedAt:     { from: QbCashReward.confirmedAt }
  disputedAt:      { from: QbCashReward.disputedAt }
  resolution:      { from: QbCashReward.resolution }
list:
  filters: [status, dueAt]
  sorts:   [dueAt, amount, status]
  defaultSort: { field: dueAt, asc: false }
  key: [cashRewardId]
permissions: [qb.me]
---*/
-- Open: owed, marked paid (confirm or dispute), overdue (the taker starts no new quiz until they do) or disputed.
SELECT
    r.{{QbCashReward.cashRewardId}}  AS cashRewardId,
    r.{{QbCashReward.attemptId}}     AS attemptId,
    r.{{QbCashReward.code}}          AS code,
    r.{{QbCashReward.publicationId}} AS publicationId,
    c.{{QbPublicationCard.name}}     AS publicationName,
    sp.{{QbSponsor.company}}         AS sponsorCompany,
    r.{{QbCashReward.amount}}        AS amount,
    r.{{QbCashReward.status}}        AS status,
    r.{{QbCashReward.dueAt}}         AS dueAt,
    r.{{QbCashReward.markedPaidAt}}  AS markedPaidAt,
    r.{{QbCashReward.confirmBy}}     AS confirmBy,
    r.{{QbCashReward.confirmedAt}}   AS confirmedAt,
    r.{{QbCashReward.disputedAt}}    AS disputedAt,
    r.{{QbCashReward.resolution}}    AS resolution
FROM {{QbCashReward}} r
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = r.{{QbCashReward.publicationId}}
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = r.{{QbCashReward.sponsorId}}
WHERE (CAST(:open AS boolean) IS NULL OR CAST(:open AS boolean) = false
       OR r.{{QbCashReward.status}} IN ('DUE', 'MARKED_PAID', 'OVERDUE', 'DISPUTED'))
