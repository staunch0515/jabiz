/*---
id: qb.admin.region-refusals
description: >-
  How many starts the rule on foreign videos refused (F-S11), by UTC day, newest first: only the counts, never who or
  from which address; the last hour's are written by the next run of the job qb.region-refusals
entities: [QbRegionRefusalDay]
datasets:
  QbRegionRefusalDay: urn:jabiz:dataset:default:QbRegionRefusalDay
params:
  from: { kind: { type: date }, description: The first day; all when not given }
results:
  day:     { from: QbRegionRefusalDay.day }
  refused: { from: QbRegionRefusalDay.refused }
list:
  sorts: [day]
  defaultSort: { field: day, asc: false }
  key: [day]
permissions: [qb.admin.region.read]
---*/
SELECT
    d.{{QbRegionRefusalDay.day}}     AS day,
    d.{{QbRegionRefusalDay.refused}} AS refused
FROM {{QbRegionRefusalDay}} d
WHERE (CAST(:from AS date) IS NULL OR d.{{QbRegionRefusalDay.day}} >= CAST(:from AS date))
