/*---
id: qb.sponsor.exposure
description: >-
  F-S13: the exposures of the Sponsor's publications that use another's shared video: the purchased exposures (none:
  unlimited), the successes, those charged (no more than purchased) and beyond, the pushes opened and the takers who
  went on to answer. Counts only, never who
entities: [QbPublicationCard, QbExposure]
datasets:
  QbPublicationCard: urn:jabiz:dataset:sponsor:QbPublicationCard
  QbExposure: urn:jabiz:dataset:default:QbExposure
params:
  publicationId: { kind: { type: text, maxLength: 36 }, description: "Only this publication; all of the Sponsor's when not given" }
results:
  publicationId: { from: QbPublicationCard.publicationId }
  name:          { from: QbPublicationCard.name }
  status:        { from: QbPublicationCard.status }
  purchased:     { from: QbPublicationCard.exposurePurchased }
  successes:     { kind: { type: numeric, precision: 12, scale: 0 } }
  billable:      { kind: { type: numeric, precision: 12, scale: 0 } }
  overshoot:     { kind: { type: numeric, precision: 12, scale: 0 } }
  opens:         { kind: { type: numeric, precision: 12, scale: 0 } }
  goes:          { kind: { type: numeric, precision: 12, scale: 0 } }
list:
  sorts: [publicationId]
  defaultSort: { field: publicationId, asc: false }
  key: [publicationId]
permissions: [qb.sponsor.me]
---*/
-- The Sponsor's own cards (their dataset holds the caller's only) whose video is another's shared video
-- (exposure.ExposureRules.othersVideo). Charged: min(successes, purchased), beyond: the rest (ExposureRules.billable,
-- overshoot; F-M8 settles the first); none purchased charges all and is never used up.
SELECT c.{{QbPublicationCard.publicationId}} AS publicationId, c.{{QbPublicationCard.name}} AS name,
       c.{{QbPublicationCard.status}} AS status, c.{{QbPublicationCard.exposurePurchased}} AS purchased,
       x.successes AS successes,
       CASE WHEN c.{{QbPublicationCard.exposurePurchased}} IS NULL THEN x.successes
            ELSE least(x.successes, c.{{QbPublicationCard.exposurePurchased}}) END AS billable,
       CASE WHEN c.{{QbPublicationCard.exposurePurchased}} IS NULL THEN 0
            ELSE greatest(0, x.successes - c.{{QbPublicationCard.exposurePurchased}}) END AS overshoot,
       x.opens AS opens, x.goes AS goes
FROM {{QbPublicationCard}} c
CROSS JOIN LATERAL (
    SELECT count(*) FILTER (WHERE e.{{QbExposure.countedAt}} IS NOT NULL) AS successes,
           count(*) FILTER (WHERE e.{{QbExposure.openedAt}} IS NOT NULL) AS opens,
           count(*) FILTER (WHERE e.{{QbExposure.wentToAnswerAt}} IS NOT NULL) AS goes
    FROM {{QbExposure}} e
    WHERE e.{{QbExposure.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)) x
WHERE c.{{QbPublicationCard.sharedVideoId}} IS NOT NULL
  AND c.{{QbPublicationCard.videoProviderId}} IS NOT NULL
  AND c.{{QbPublicationCard.videoProviderId}} <> c.{{QbPublicationCard.publisherId}}
  AND (CAST(:publicationId AS text) IS NULL
       OR CAST(c.{{QbPublicationCard.publicationId}} AS text) = CAST(:publicationId AS text))
