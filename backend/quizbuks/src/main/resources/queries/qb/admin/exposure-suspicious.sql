/*---
id: qb.admin.exposure-suspicious
description: >-
  F-S13: the suspicious inflation of exposures: the accounts the daily cap refused, by account, most hits first: how
  often (a publication a UTC day each), on how many publications, the last day, and the exposures they counted.
  Headquarters reads every sponsor's publications, an operator its own sponsors' only
entities: [QbExposure]
datasets:
  QbExposure: urn:jabiz:dataset:default:QbExposure
params:
  since: { kind: { type: date }, description: "Only the accounts the cap refused on or after this day (UTC); all when not given" }
results:
  userId:       { from: QbExposure.userId }
  hits:         { kind: { type: numeric, precision: 12, scale: 0 } }
  publications: { kind: { type: numeric, precision: 12, scale: 0 } }
  lastCappedOn: { from: QbExposure.lastCappedOn }
  successes:    { kind: { type: numeric, precision: 12, scale: 0 } }
list:
  filters: [userId]
  sorts:   [hits, lastCappedOn, successes]
  defaultSort: { field: hits, asc: false }
  key: [userId]
permissions: [qb.admin.exposure.read]
---*/
-- The rows of the publications the caller looks after (an operator's session reads its own sponsors' only, the entry
-- scope), grouped by taker; only takers the cap refused at least once (the partial index on capped_days).
SELECT e.{{QbExposure.userId}} AS userId,
       sum(e.{{QbExposure.cappedDays}}) AS hits,
       count(*) FILTER (WHERE e.{{QbExposure.cappedDays}} > 0) AS publications,
       max(e.{{QbExposure.lastCappedOn}}) AS lastCappedOn,
       count(*) FILTER (WHERE e.{{QbExposure.countedAt}} IS NOT NULL) AS successes
FROM {{QbExposure}} e
WHERE e.{{QbExposure.userId}} IN (SELECT c.{{QbExposure.userId}} FROM {{QbExposure}} c
                                  WHERE c.{{QbExposure.cappedDays}} > 0)
GROUP BY e.{{QbExposure.userId}}
HAVING sum(e.{{QbExposure.cappedDays}}) > 0
   AND (CAST(:since AS date) IS NULL OR max(e.{{QbExposure.lastCappedOn}}) >= CAST(:since AS date))
