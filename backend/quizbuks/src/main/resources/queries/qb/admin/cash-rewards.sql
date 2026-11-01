/*---
id: qb.admin.cash-rewards
description: "Every cash reward (Q22) with its sponsor and taker, newest first; disputes and late sponsors are found here"
entities: [QbCashReward, QbPublicationCard, QbSponsor, QbProfile]
datasets:
  QbCashReward: urn:jabiz:dataset:default:QbCashReward
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbProfile: urn:jabiz:dataset:default:QbProfile
params:
  q: { kind: { type: text, maxLength: 100 }, description: "Only rewards whose code, the sponsor's company or the taker's name contains this text" }
results:
  cashRewardId:    { from: QbCashReward.cashRewardId }
  code:            { from: QbCashReward.code }
  attemptId:       { from: QbCashReward.attemptId }
  sourceKind:      { from: QbCashReward.sourceKind }
  publicationId:   { from: QbCashReward.publicationId }
  publicationName: { from: QbPublicationCard.name }
  publisherKind:   { from: QbCashReward.publisherKind }
  publisherId:     { from: QbCashReward.publisherId }
  sponsorCompany:  { from: QbSponsor.company }
  userId:          { from: QbCashReward.userId }
  takerName:       { from: QbProfile.displayName }
  amount:          { from: QbCashReward.amount }
  status:          { from: QbCashReward.status }
  dueAt:           { from: QbCashReward.dueAt }
  payBy:           { from: QbCashReward.payBy }
  remindedAt:      { from: QbCashReward.remindedAt }
  markedPaidAt:    { from: QbCashReward.markedPaidAt }
  markedPaidNote:  { from: QbCashReward.markedPaidNote }
  confirmBy:       { from: QbCashReward.confirmBy }
  confirmedAt:     { from: QbCashReward.confirmedAt }
  disputedAt:      { from: QbCashReward.disputedAt }
  disputeNote:     { from: QbCashReward.disputeNote }
  overdueAt:       { from: QbCashReward.overdueAt }
  resolvedAt:      { from: QbCashReward.resolvedAt }
  resolution:      { from: QbCashReward.resolution }
  resolveNote:     { from: QbCashReward.resolveNote }
list:
  filters: [status, publisherKind, publisherId, userId, publicationId, dueAt, payBy, amount]
  sorts:   [dueAt, payBy, amount, status, disputedAt]
  defaultSort: { field: dueAt, asc: false }
  key: [cashRewardId]
permissions: [qb.admin.publication.read]
---*/
-- The text is found as written: strpos takes it literally, so % _ and \ match themselves.
SELECT
    r.{{QbCashReward.cashRewardId}}   AS cashRewardId,
    r.{{QbCashReward.code}}           AS code,
    r.{{QbCashReward.attemptId}}      AS attemptId,
    r.{{QbCashReward.sourceKind}}      AS sourceKind,
    r.{{QbCashReward.publicationId}}  AS publicationId,
    c.{{QbPublicationCard.name}}      AS publicationName,
    r.{{QbCashReward.publisherKind}}    AS publisherKind,
    r.{{QbCashReward.publisherId}}      AS publisherId,
    sp.{{QbSponsor.company}}          AS sponsorCompany,
    r.{{QbCashReward.userId}}         AS userId,
    p.{{QbProfile.displayName}}       AS takerName,
    r.{{QbCashReward.amount}}         AS amount,
    r.{{QbCashReward.status}}         AS status,
    r.{{QbCashReward.dueAt}}          AS dueAt,
    r.{{QbCashReward.payBy}}          AS payBy,
    r.{{QbCashReward.remindedAt}}     AS remindedAt,
    r.{{QbCashReward.markedPaidAt}}   AS markedPaidAt,
    r.{{QbCashReward.markedPaidNote}} AS markedPaidNote,
    r.{{QbCashReward.confirmBy}}      AS confirmBy,
    r.{{QbCashReward.confirmedAt}}    AS confirmedAt,
    r.{{QbCashReward.disputedAt}}     AS disputedAt,
    r.{{QbCashReward.disputeNote}}    AS disputeNote,
    r.{{QbCashReward.overdueAt}}      AS overdueAt,
    r.{{QbCashReward.resolvedAt}}     AS resolvedAt,
    r.{{QbCashReward.resolution}}     AS resolution,
    r.{{QbCashReward.resolveNote}}    AS resolveNote
FROM {{QbCashReward}} r
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = r.{{QbCashReward.publicationId}}
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = r.{{QbCashReward.publisherId}}
LEFT JOIN {{QbProfile}} p ON p.{{QbProfile.userId}} = r.{{QbCashReward.userId}}
WHERE (CAST(:q AS text) IS NULL
       OR strpos(lower(r.{{QbCashReward.code}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(sp.{{QbSponsor.company}}, '')), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(p.{{QbProfile.displayName}}, '')), lower(CAST(:q AS text))) > 0)
