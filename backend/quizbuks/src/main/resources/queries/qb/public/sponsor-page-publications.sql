/*---
id: qb.public.sponsor-page-publications
description: >-
  The live public publications of the sponsor whose published page has the handle (Q60a): what the page lists, each
  linking to its share page; none when the page is not published
access: public
cacheSeconds: 60
timeoutMs: 2000
entities: [QbRegionException, QbPublicationCard, QbSponsorPage, QbSponsor]
datasets:
  QbRegionException: urn:jabiz:dataset:public:QbRegionException
  QbPublicationCard: urn:jabiz:dataset:public:QbPublicationCard
  QbSponsorPage: urn:jabiz:dataset:public:QbSponsorPage
  QbSponsor: urn:jabiz:dataset:public:QbSponsor
params:
  handle: { like: QbSponsorPage.handle, required: true, description: The page's handle (lowercase) }
  mainlandChina: { kind: { type: bool }, description: "F-S11: whether the visitor's address is in mainland China, as GET /api/client/region said" }
  at: { kind: { type: temporal, role: EVENT_TIME }, description: "F-S11: the visitor's now (rounded to the minute), for the exceptions' last day" }
results:
  publicationId: { from: QbPublicationCard.publicationId }
  name:          { from: QbPublicationCard.name }
  quizTitle:     { from: QbPublicationCard.quizTitle }
  kind:          { from: QbPublicationCard.kind }
  startAt:       { from: QbPublicationCard.startAt }
  endAt:         { from: QbPublicationCard.endAt }
  timeLimitSec:  { from: QbPublicationCard.timeLimitSec }
  questionCount: { from: QbPublicationCard.questionCount }
  pickCount:     { from: QbPublicationCard.pickCount }
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
    c.{{QbPublicationCard.pickCount}}     AS pickCount,
    c.{{QbPublicationCard.maxReward}}     AS maxReward,
    c.{{QbPublicationCard.rewardMethod}}  AS rewardMethod
FROM {{QbPublicationCard}} c
JOIN {{QbSponsorPage}} p ON p.{{QbSponsorPage.userId}} = c.{{QbPublicationCard.publisherId}}
JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = p.{{QbSponsorPage.userId}}
WHERE p.{{QbSponsorPage.handle}} = :handle
  -- F-S11: the visitor's page passes whether its address is in mainland China (GET /api/client/region): the result
  -- depends on the parameters only, never on the address, so the shared cache serves it like any other. A publication with
  -- a foreign video, or none recorded yet, is not for mainland China unless an exception of headquarters is in force on
  -- the day of :at (without :at, only one with no last day counts). The start checks the address itself.
  AND NOT (CAST(:mainlandChina AS boolean) IS TRUE AND coalesce(c.{{QbPublicationCard.foreignVideo}}, true)
         AND NOT EXISTS (
             SELECT 1 FROM {{QbRegionException}} rx
             WHERE (rx.{{QbRegionException.expiresOn}} IS NULL
                    OR rx.{{QbRegionException.expiresOn}} >= CAST(CAST(:at AS timestamptz) AT TIME ZONE 'UTC' AS date))
               AND ((rx.{{QbRegionException.kind}} = 'PUBLICATION'
                     AND rx.{{QbRegionException.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text))
                    OR (rx.{{QbRegionException.kind}} = 'SPONSOR'
                        AND rx.{{QbRegionException.sponsorId}} = c.{{QbPublicationCard.publisherId}}))))
