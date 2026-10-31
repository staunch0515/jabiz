/*---
id: qb.public.publication
description: >-
  A live publication as its share link shows it to anybody before signing in (Q15): the quiz's title, the sponsor's
  company, the reward, the number of questions and the time limit; none when it is not live
access: public
cacheSeconds: 60
timeoutMs: 2000
entities: [QbPublicationCard, QbSponsor, QbSponsorPage]
datasets:
  QbPublicationCard: urn:jabiz:dataset:public:QbPublicationCard
  QbSponsor: urn:jabiz:dataset:public:QbSponsor
  QbSponsorPage: urn:jabiz:dataset:public:QbSponsorPage
params:
  publicationId: { like: QbPublicationCard.publicationId, required: true, description: The publication }
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
  rewardMethod:   { from: QbPublicationCard.rewardMethod }
  redeemDescription: { from: QbPublicationCard.redeemDescription }
  digitalDelivery: { from: QbPublicationCard.digitalDelivery }
  digitalDescription: { from: QbPublicationCard.digitalDescription }
  sponsorCompany: { from: QbSponsor.company }
  sponsorHandle:  { from: QbSponsorPage.handle }
list:
  key: [publicationId]
---*/
-- The public dataset holds live cards only (its fixed scope), and only approved sponsors' companies: a scheduled,
-- paused or ended publication is no row. Live is the server's state (the minute's job ends it), not the visitor's
-- clock. Who may start it (the regions, the budget, the winners, one attempt per taker) is decided after signing in,
-- by the app's own page and QB_ATTEMPT_START.
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
    c.{{QbPublicationCard.rewardMethod}}  AS rewardMethod,
    c.{{QbPublicationCard.redeemDescription}} AS redeemDescription,
    c.{{QbPublicationCard.digitalDelivery}} AS digitalDelivery,
    c.{{QbPublicationCard.digitalDescription}} AS digitalDescription,
    sp.{{QbSponsor.company}}              AS sponsorCompany,
    -- Q60a: the sponsor's published page, which the sponsor's name links to.
    pg.{{QbSponsorPage.handle}}           AS sponsorHandle
FROM {{QbPublicationCard}} c
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = c.{{QbPublicationCard.publisherId}}
LEFT JOIN {{QbSponsorPage}} pg ON pg.{{QbSponsorPage.userId}} = sp.{{QbSponsor.userId}}
WHERE c.{{QbPublicationCard.publicationId}} = :publicationId
