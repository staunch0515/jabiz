/*---
id: qb.admin.sponsor-stats
description: >-
  One sponsor's page views and product clicks in a period (Q60c), for headquarters and the operator looking after
  the sponsor: visitors counted once a day per page or product, approximate (platform decision D65)
entities: [PublicCounterDay]
datasets:
  PublicCounterDay: urn:jabiz:dataset:admin:PublicCounterDay
params:
  userId: { like: PublicCounterDay.ownerKey, required: true, description: The sponsor }
  from:   { kind: { type: date }, required: true, description: The first day }
  to:     { kind: { type: date }, required: true, description: The last day }
results:
  views:  { kind: { type: numeric, precision: 18, scale: 0 } }
  clicks: { kind: { type: numeric, precision: 18, scale: 0 } }
list:
  key: [views]
permissions: [qb.admin.publication.read]
---*/
-- The administration's dataset of counts, limited for an operator's session to the sponsors it looks after (by the
-- counts' owner). Always one row.
SELECT
    coalesce(sum(c.{{PublicCounterDay.hits}}) FILTER (
        WHERE c.{{PublicCounterDay.counterName}} = 'qb.sponsor-page-view'), 0) AS views,
    coalesce(sum(c.{{PublicCounterDay.hits}}) FILTER (
        WHERE c.{{PublicCounterDay.counterName}} = 'qb.product-click'), 0) AS clicks
FROM {{PublicCounterDay}} c
WHERE c.{{PublicCounterDay.ownerKey}} = CAST(:userId AS text)
    AND c.{{PublicCounterDay.day}} BETWEEN CAST(:from AS date) AND CAST(:to AS date)
