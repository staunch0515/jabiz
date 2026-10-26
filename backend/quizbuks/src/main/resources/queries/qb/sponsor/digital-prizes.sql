/*---
id: qb.sponsor.digital-prizes
description: "The digital prizes the sponsor's publications awarded (Q33), newest first: to send, delivered, disputed; searched by taker"
entities: [QbDigitalPrize, QbPublicationCard, QbProfile]
datasets:
  QbDigitalPrize: urn:jabiz:dataset:sponsor:QbDigitalPrize
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbProfile: urn:jabiz:dataset:default:QbProfile
params:
  q: { kind: { type: text, maxLength: 100 }, description: "Only prizes whose taker's name or publication contains this text" }
results:
  digitalPrizeId:  { from: QbDigitalPrize.digitalPrizeId }
  publicationId:   { from: QbDigitalPrize.publicationId }
  publicationName: { from: QbPublicationCard.name }
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
  resolution:      { from: QbDigitalPrize.resolution }
  resolveNote:     { from: QbDigitalPrize.resolveNote }
list:
  filters: [status, delivery, publicationId, dueAt, amount]
  sorts:   [dueAt, amount, status, deliveredAt, sendBy]
  defaultSort: { field: dueAt, asc: false }
  key: [digitalPrizeId]
permissions: [qb.publication.write]
---*/
-- The taker's name as their profile gives it (none without a profile). The content is sealed and sensitive: not
-- selectable here; the hint tells codes of a pool apart. The text is found as written: strpos takes it literally.
SELECT
    g.{{QbDigitalPrize.digitalPrizeId}} AS digitalPrizeId,
    g.{{QbDigitalPrize.publicationId}}  AS publicationId,
    c.{{QbPublicationCard.name}}        AS publicationName,
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
    g.{{QbDigitalPrize.resolution}}     AS resolution,
    g.{{QbDigitalPrize.resolveNote}}    AS resolveNote
FROM {{QbDigitalPrize}} g
LEFT JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = g.{{QbDigitalPrize.publicationId}}
LEFT JOIN {{QbProfile}} p ON p.{{QbProfile.userId}} = g.{{QbDigitalPrize.userId}}
WHERE (CAST(:q AS text) IS NULL
       OR strpos(lower(coalesce(p.{{QbProfile.displayName}}, '')), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(c.{{QbPublicationCard.name}}, '')), lower(CAST(:q AS text))) > 0)
