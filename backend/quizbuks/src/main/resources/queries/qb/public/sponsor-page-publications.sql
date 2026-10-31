/*---
id: qb.public.sponsor-page-publications
description: >-
  The live public publications of the sponsor whose published page has the handle (Q60a): what the page lists, each
  linking to its share page; none when the page is not published
access: public
cacheSeconds: 60
timeoutMs: 2000
entities: [QbPublicationCard, QbSponsorPage, QbSponsor]
datasets:
  QbPublicationCard: urn:jabiz:dataset:public:QbPublicationCard
  QbSponsorPage: urn:jabiz:dataset:public:QbSponsorPage
  QbSponsor: urn:jabiz:dataset:public:QbSponsor
params:
  handle: { like: QbSponsorPage.handle, required: true, description: The page's handle (lowercase) }
results:
  publicationId: { from: QbPublicationCard.publicationId }
  name:          { from: QbPublicationCard.name }
  quizTitle:     { from: QbPublicationCard.quizTitle }
  kind:          { from: QbPublicationCard.kind }
  startAt:       { from: QbPublicationCard.startAt }
  endAt:         { from: QbPublicationCard.endAt }
  timeLimitSec:  { from: QbPublicationCard.timeLimitSec }
  questionCount: { from: QbPublicationCard.questionCount }
  maxReward:     { from: QbPublicationCard.maxReward }
  rewardMethod:  { from: QbPublicationCard.rewardMethod }
list:
  key: [publicationId]
  sorts: [endAt]
  defaultSort: { field: endAt, asc: true }
---*/
-- Live public cards only (their dataset's fixed scope): who may start one is decided after signing in, as on the
-- share page. The page shows only while its sponsor is approved, as qb.public.sponsor-page does.
SELECT
    c.{{QbPublicationCard.publicationId}} AS publicationId,
    c.{{QbPublicationCard.name}}          AS name,
    c.{{QbPublicationCard.quizTitle}}     AS quizTitle,
    c.{{QbPublicationCard.kind}}          AS kind,
    c.{{QbPublicationCard.startAt}}       AS startAt,
    c.{{QbPublicationCard.endAt}}         AS endAt,
    c.{{QbPublicationCard.timeLimitSec}}  AS timeLimitSec,
    c.{{QbPublicationCard.questionCount}} AS questionCount,
    c.{{QbPublicationCard.maxReward}}     AS maxReward,
    c.{{QbPublicationCard.rewardMethod}}  AS rewardMethod
FROM {{QbPublicationCard}} c
JOIN {{QbSponsorPage}} p ON p.{{QbSponsorPage.userId}} = c.{{QbPublicationCard.publisherId}}
JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = p.{{QbSponsorPage.userId}}
WHERE p.{{QbSponsorPage.handle}} = :handle
