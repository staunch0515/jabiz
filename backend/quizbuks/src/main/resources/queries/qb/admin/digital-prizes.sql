/*---
id: qb.admin.digital-prizes
description: "Every digital prize (Q33) with its sponsor and taker, newest first; disputes are decided from here"
entities: [QbDigitalPrize, QbPublicationCard, QbSponsor, QbProfile]
datasets:
  QbDigitalPrize: urn:jabiz:dataset:default:QbDigitalPrize
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbProfile: urn:jabiz:dataset:default:QbProfile
params:
  q: { kind: { type: text, maxLength: 100 }, description: "Only prizes whose sponsor's company, the taker's name or the publication contains this text" }
results:
  digitalPrizeId:  { from: QbDigitalPrize.digitalPrizeId }
  attemptId:       { from: QbDigitalPrize.attemptId }
  publicationId:   { from: QbDigitalPrize.publicationId }
  publicationName: { from: QbPublicationCard.name }
  sponsorId:       { from: QbDigitalPrize.sponsorId }
  sponsorCompany:  { from: QbSponsor.company }
  userId:          { from: QbDigitalPrize.userId }
  takerName:       { from: QbProfile.displayName }
  amount:          { from: QbDigitalPrize.amount }
  description:     { from: QbDigitalPrize.description }
  delivery:        { from: QbDigitalPrize.delivery }
  hint:            { from: QbDigitalPrize.hint }
  status:          { from: QbDigitalPrize.status }
  dueAt:           { from: QbDigitalPrize.dueAt }
  sendBy:          { from: QbDigitalPrize.sendBy }
  deliveredAt:     { from: QbDigitalPrize.deliveredAt }
  deliveredBy:     { from: QbDigitalPrize.deliveredBy }
  openedAt:        { from: QbDigitalPrize.openedAt }
  confirmBy:       { from: QbDigitalPrize.confirmBy }
  confirmedAt:     { from: QbDigitalPrize.confirmedAt }
  disputedAt:      { from: QbDigitalPrize.disputedAt }
  disputeNote:     { from: QbDigitalPrize.disputeNote }
  resolvedAt:      { from: QbDigitalPrize.resolvedAt }
  resolution:      { from: QbDigitalPrize.resolution }
  resolveNote:     { from: QbDigitalPrize.resolveNote }
list:
  filters: [status, delivery, sponsorId, userId, publicationId, dueAt, amount]
  sorts:   [dueAt, deliveredAt, amount, status]
  defaultSort: { field: dueAt, asc: false }
  key: [digitalPrizeId]
permissions: [qb.admin.publication.read]
---*/
-- The text is found as written: strpos takes it literally, so % _ and \ match themselves.
SELECT
    g.{{QbDigitalPrize.digitalPrizeId}} AS digitalPrizeId,
    g.{{QbDigitalPrize.attemptId}}      AS attemptId,
    g.{{QbDigitalPrize.publicationId}}  AS publicationId,
    c.{{QbPublicationCard.name}}        AS publicationName,
    g.{{QbDigitalPrize.sponsorId}}      AS sponsorId,
    sp.{{QbSponsor.company}}            AS sponsorCompany,
    g.{{QbDigitalPrize.userId}}         AS userId,
    p.{{QbProfile.displayName}}         AS takerName,
    g.{{QbDigitalPrize.amount}}         AS amount,
    g.{{QbDigitalPrize.description}}    AS description,
    g.{{QbDigitalPrize.delivery}}       AS delivery,
    g.{{QbDigitalPrize.hint}}           AS hint,
    g.{{QbDigitalPrize.status}}         AS status,
    g.{{QbDigitalPrize.dueAt}}          AS dueAt,
    g.{{QbDigitalPrize.sendBy}}         AS sendBy,
    g.{{QbDigitalPrize.deliveredAt}}    AS deliveredAt,
    g.{{QbDigitalPrize.deliveredBy}}    AS deliveredBy,
    g.{{QbDigitalPrize.openedAt}}       AS openedAt,
    g.{{QbDigitalPrize.confirmBy}}      AS confirmBy,
    g.{{QbDigitalPrize.confirmedAt}}    AS confirmedAt,
    g.{{QbDigitalPrize.disputedAt}}     AS disputedAt,
    g.{{QbDigitalPrize.disputeNote}}    AS disputeNote,
    g.{{QbDigitalPrize.resolvedAt}}     AS resolvedAt,
    g.{{QbDigitalPrize.resolution}}     AS resolution,
    g.{{QbDigitalPrize.resolveNote}}    AS resolveNote
FROM {{QbDigitalPrize}} g
LEFT JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = g.{{QbDigitalPrize.publicationId}}
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = g.{{QbDigitalPrize.sponsorId}}
LEFT JOIN {{QbProfile}} p ON p.{{QbProfile.userId}} = g.{{QbDigitalPrize.userId}}
WHERE (CAST(:q AS text) IS NULL
       OR strpos(lower(coalesce(sp.{{QbSponsor.company}}, '')), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(p.{{QbProfile.displayName}}, '')), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(c.{{QbPublicationCard.name}}, '')), lower(CAST(:q AS text))) > 0)
