/*---
id: qb.public.publication
description: >-
  A live publication as its share link shows it to anybody before signing in (Q15): the quiz's title, the sponsor's
  company, the reward, the number of questions and the time limit; none when it is not live at the time
access: public
cacheSeconds: 60
timeoutMs: 2000
entities: [QbPublicationCard, QbSponsor]
datasets:
  QbPublicationCard: urn:jabiz:dataset:public:QbPublicationCard
  QbSponsor: urn:jabiz:dataset:public:QbSponsor
params:
  publicationId: { like: QbPublicationCard.publicationId, required: true, description: The publication }
  at:            { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The time (the page's now, to the minute)" }
results:
  publicationId:  { from: QbPublicationCard.publicationId }
  name:           { from: QbPublicationCard.name }
  quizTitle:      { from: QbPublicationCard.quizTitle }
  kind:           { from: QbPublicationCard.kind }
  startAt:        { from: QbPublicationCard.startAt }
  endAt:          { from: QbPublicationCard.endAt }
  timeLimitSec:   { from: QbPublicationCard.timeLimitSec }
  questionCount:  { from: QbPublicationCard.questionCount }
  fullScore:      { from: QbPublicationCard.fullScore }
  passScore:      { from: QbPublicationCard.passScore }
  maxReward:      { from: QbPublicationCard.maxReward }
  sponsorCompany: { from: QbSponsor.company }
list:
  key: [publicationId]
---*/
-- The public dataset holds live cards only (its fixed scope), and only approved sponsors' companies: a paused or
-- ended publication, or one whose time has not come or has passed, is no row. Who may start it (the regions, the
-- budget, the winners, one attempt per taker) is decided after signing in, by the app's own page and QB_ATTEMPT_START.
SELECT
    c.{{QbPublicationCard.publicationId}} AS publicationId,
    c.{{QbPublicationCard.name}}          AS name,
    c.{{QbPublicationCard.quizTitle}}     AS quizTitle,
    c.{{QbPublicationCard.kind}}          AS kind,
    c.{{QbPublicationCard.startAt}}       AS startAt,
    c.{{QbPublicationCard.endAt}}         AS endAt,
    c.{{QbPublicationCard.timeLimitSec}}  AS timeLimitSec,
    c.{{QbPublicationCard.questionCount}} AS questionCount,
    c.{{QbPublicationCard.fullScore}}     AS fullScore,
    c.{{QbPublicationCard.passScore}}     AS passScore,
    c.{{QbPublicationCard.maxReward}}     AS maxReward,
    sp.{{QbSponsor.company}}              AS sponsorCompany
FROM {{QbPublicationCard}} c
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = c.{{QbPublicationCard.sponsorId}}
WHERE c.{{QbPublicationCard.publicationId}} = :publicationId
  AND c.{{QbPublicationCard.startAt}} <= :at
  AND c.{{QbPublicationCard.endAt}} > :at
