/*---
id: qb.sponsor.page-stats
description: >-
  The views of the sponsor's page and the clicks on their products' shop links by day (Q60c), every day of the period
  present (zeros included): visitors counted once a day per page or product, approximate (platform decision D65)
entities: [PublicCounterDay]
datasets:
  PublicCounterDay: urn:jabiz:dataset:me:PublicCounterDay
params:
  from: { kind: { type: date }, required: true, description: The first day }
  to:   { kind: { type: date }, required: true, description: The last day (at most 400 days after the first) }
results:
  day:    { kind: { type: date } }
  views:  { kind: { type: numeric, precision: 18, scale: 0 } }
  clicks: { kind: { type: numeric, precision: 18, scale: 0 } }
list:
  sorts: [day]
  defaultSort: { field: day, asc: true }
  key: [day]
permissions: [qb.sponsor.me]
---*/
-- Only the caller's counts: the dataset holds the rows whose owner is the caller (their page's views by handle, any
-- handle it had, and their products' clicks). The days are those of the counters (jabiz.fiscal-year-zone).
WITH days AS (
    SELECT CAST(d AS date) AS day
    FROM generate_series(CAST(:from AS date),
        least(CAST(:to AS date), CAST(:from AS date) + 399), interval '1 day') d)
SELECT days.day,
    coalesce(sum(c.{{PublicCounterDay.hits}}) FILTER (
        WHERE c.{{PublicCounterDay.counterName}} = 'qb.sponsor-page-view'), 0) AS views,
    coalesce(sum(c.{{PublicCounterDay.hits}}) FILTER (
        WHERE c.{{PublicCounterDay.counterName}} = 'qb.product-click'), 0) AS clicks
FROM days
LEFT JOIN {{PublicCounterDay}} c ON c.{{PublicCounterDay.day}} = days.day
GROUP BY days.day
