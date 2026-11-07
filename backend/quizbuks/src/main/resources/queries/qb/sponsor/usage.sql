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
-- Always one row. The figures are the snapshot's (the job's, once a day: what was uploaded today shows tomorrow);
-- the free amount is the parameter's value in effect now (null when not set or not a whole number: no notice then).
-- "Above" is UsageRules.over: storage > free amount.
WITH latest AS (
    SELECT max(d.{{QbUsageDay.day}}) AS v_day FROM {{QbUsageDay}} d),
figures AS (
    SELECT
        max(CASE WHEN d.{{QbUsageDay.meter}} = 'storage.bytes' THEN d.{{QbUsageDay.value}} END) AS v_bytes,
        max(CASE WHEN d.{{QbUsageDay.meter}} = 'storage.files' THEN d.{{QbUsageDay.value}} END) AS v_files,
        max(CASE WHEN d.{{QbUsageDay.meter}} = 'assets.count' THEN d.{{QbUsageDay.value}} END) AS v_assets
    FROM {{QbUsageDay}} d
    JOIN latest l ON d.{{QbUsageDay.day}} = l.v_day),
free AS (
    SELECT (SELECT CAST(max(p.{{SysParam.value}}) AS numeric) FROM {{SysParam}} p
            WHERE p.{{SysParam.paramKey}} = 'qb.usage.storage.free-bytes'
              AND p.{{SysParam.value}} ~ '^[0-9]{1,15}$') AS v_free)
SELECT
    l.v_day AS day,
    CAST(f.v_bytes AS numeric(19, 0)) AS storageBytes,
    CAST(f.v_files AS numeric(19, 0)) AS storageFiles,
    CAST(f.v_assets AS numeric(19, 0)) AS assetsCount,
    CAST(r.v_free AS numeric(19, 0)) AS freeBytes,
    coalesce(f.v_bytes > r.v_free, false) AS overFree
FROM latest l
CROSS JOIN figures f
CROSS JOIN free r
