/*---
id: qb.sponsor.usage
description: >-
  The sponsor's usage card on their asset library (F-C2): the latest daily snapshot (its day; none yet before the
  first run) with the storage used, the files and the assets, and the free amount now; whether the storage is above it
  (only shown: nothing is refused, and nothing is charged in the first launch)
entities: [QbUsageDay, SysParam]
datasets:
  QbUsageDay: urn:jabiz:dataset:me:QbUsageDay
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  day:          { from: QbUsageDay.day }
  storageBytes: { kind: { type: numeric, precision: 19, scale: 0 } }
  storageFiles: { kind: { type: numeric, precision: 19, scale: 0 } }
  assetsCount:  { kind: { type: numeric, precision: 19, scale: 0 } }
  freeBytes:    { kind: { type: numeric, precision: 19, scale: 0 } }
  overFree:     { kind: { type: bool } }
permissions: [qb.content.write]
---*/
-- Always one row. The figures are the snapshot's (the job's, once a day: what was uploaded today shows tomorrow), read
-- from the day's row of meters (a JSON object, usage.UsageMeters' keys); the free amount is the parameter's value in
-- effect now (null when not set or not a whole number: no notice then). "Above" is UsageRules.over: storage > free.
WITH latest AS (
    SELECT d.{{QbUsageDay.day}} AS v_day, CAST(d.{{QbUsageDay.meters}} AS jsonb) AS v_meters
    FROM {{QbUsageDay}} d
    ORDER BY d.{{QbUsageDay.day}} DESC
    LIMIT 1),
one AS (
    SELECT
        (SELECT v_day FROM latest) AS v_day,
        (SELECT CAST(v_meters ->> 'storage.bytes' AS numeric) FROM latest) AS v_bytes,
        (SELECT CAST(v_meters ->> 'storage.files' AS numeric) FROM latest) AS v_files,
        (SELECT CAST(v_meters ->> 'assets.count' AS numeric) FROM latest) AS v_assets),
free AS (
    SELECT (SELECT CAST(max(p.{{SysParam.value}}) AS numeric) FROM {{SysParam}} p
            WHERE p.{{SysParam.paramKey}} = 'qb.usage.storage.free-bytes'
              AND p.{{SysParam.value}} ~ '^[0-9]{1,15}$') AS v_free)
SELECT
    o.v_day AS day,
    CAST(o.v_bytes AS numeric(19, 0)) AS storageBytes,
    CAST(o.v_files AS numeric(19, 0)) AS storageFiles,
    CAST(o.v_assets AS numeric(19, 0)) AS assetsCount,
    CAST(r.v_free AS numeric(19, 0)) AS freeBytes,
    coalesce(o.v_bytes > r.v_free, false) AS overFree
FROM one o
CROSS JOIN free r
