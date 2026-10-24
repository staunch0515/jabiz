/*---
id: qb.sponsor.cash-rewards
description: "The cash rewards the sponsor owes or paid (Q22), newest first; searched by code or taker"
entities: [QbCashReward, QbPublicationCard, QbProfile]
datasets:
  QbCashReward: urn:jabiz:dataset:sponsor:QbCashReward
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbProfile: urn:jabiz:dataset:default:QbProfile
params:
  q: { kind: { type: text, maxLength: 100 }, description: "Only rewards whose code or the taker's name contains this text" }
results:
  cashRewardId:   { from: QbCashReward.cashRewardId }
  code:           { from: QbCashReward.code }
  publicationId:  { from: QbCashReward.publicationId }
  publicationName: { from: QbPublicationCard.name }
  takerName:      { from: QbProfile.displayName }
  amount:         { from: QbCashReward.amount }
  status:         { from: QbCashReward.status }
  dueAt:          { from: QbCashReward.dueAt }
  payBy:          { from: QbCashReward.payBy }
  markedPaidAt:   { from: QbCashReward.markedPaidAt }
  markedPaidNote: { from: QbCashReward.markedPaidNote }
  confirmBy:      { from: QbCashReward.confirmBy }
  confirmedAt:    { from: QbCashReward.confirmedAt }
  disputedAt:     { from: QbCashReward.disputedAt }
  disputeNote:    { from: QbCashReward.disputeNote }
  resolution:     { from: QbCashReward.resolution }
  resolveNote:    { from: QbCashReward.resolveNote }
list:
  filters: [status, publicationId, dueAt, amount]
  sorts:   [dueAt, amount, status, markedPaidAt]
  defaultSort: { field: dueAt, asc: false }
  key: [cashRewardId]
permissions: [qb.publication.write]
---*/
-- The taker's name as their profile gives it (none without a profile): what the sponsor asks at the counter, with the
-- code. The text is found as written: strpos takes it literally, so % _ and \ match themselves.
SELECT
    r.{{QbCashReward.cashRewardId}}   AS cashRewardId,
    r.{{QbCashReward.code}}           AS code,
    r.{{QbCashReward.publicationId}}  AS publicationId,
    c.{{QbPublicationCard.name}}      AS publicationName,
    p.{{QbProfile.displayName}}       AS takerName,
    r.{{QbCashReward.amount}}         AS amount,
    r.{{QbCashReward.status}}         AS status,
    r.{{QbCashReward.dueAt}}          AS dueAt,
    r.{{QbCashReward.payBy}}          AS payBy,
    r.{{QbCashReward.markedPaidAt}}   AS markedPaidAt,
    r.{{QbCashReward.markedPaidNote}} AS markedPaidNote,
    r.{{QbCashReward.confirmBy}}      AS confirmBy,
    r.{{QbCashReward.confirmedAt}}    AS confirmedAt,
    r.{{QbCashReward.disputedAt}}     AS disputedAt,
    r.{{QbCashReward.disputeNote}}    AS disputeNote,
    r.{{QbCashReward.resolution}}     AS resolution,
    r.{{QbCashReward.resolveNote}}    AS resolveNote
FROM {{QbCashReward}} r
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = r.{{QbCashReward.publicationId}}
LEFT JOIN {{QbProfile}} p ON p.{{QbProfile.userId}} = r.{{QbCashReward.userId}}
WHERE (CAST(:q AS text) IS NULL
       OR strpos(lower(r.{{QbCashReward.code}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(p.{{QbProfile.displayName}}, '')), lower(CAST(:q AS text))) > 0)
