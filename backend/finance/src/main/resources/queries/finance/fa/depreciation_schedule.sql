/*---
id: finance.fa.depreciation_schedule
description: >-
  The depreciation taken, month by month and asset by asset (FIN-FA-009, the past part of the schedule): the lines of
  the posted runs and the depreciation of the disposal months, with what the asset had accumulated after. The months
  ahead are given by the projection of FIN_FA_SCHEDULE_PROJECT.
entities: [FinDepreciationRun, FinDepreciationLine, FinAssetDisposal, FinAsset]
params:
  fromPeriod: { like: FinDepreciationRun.periodKey, description: "the first month, as 2026-01" }
  toPeriod:   { like: FinDepreciationRun.periodKey, description: "the last month" }
  assetNo:    { like: FinDepreciationLine.assetNo, description: "one asset only" }
results:
  periodKey:   { from: FinDepreciationLine.periodKey }
  assetNo:     { from: FinDepreciationLine.assetNo }
  classCode:   { from: FinDepreciationLine.classCode }
  source:      { kind: { type: text, maxLength: 10 } }
  documentNo:  { from: FinAssetDisposal.documentNo }
  amount:      { from: FinDepreciationLine.amount }
  accumulated: { from: FinDepreciationLine.accumulated }
  units:       { from: FinDepreciationLine.units }
list:
  filters: [periodKey, assetNo, classCode, source]
  sorts:   [periodKey, assetNo, amount]
  defaultSort: { field: periodKey, asc: true }
  key: [periodKey, assetNo, source]
permissions: [fin.fa.read]
---*/
SELECT l.{{FinDepreciationLine.periodKey}} AS periodKey, l.{{FinDepreciationLine.assetNo}} AS assetNo,
       l.{{FinDepreciationLine.classCode}} AS classCode, 'RUN' AS source, r.{{FinDepreciationRun.runNo}} AS documentNo,
       l.{{FinDepreciationLine.amount}} AS amount, l.{{FinDepreciationLine.accumulated}} AS accumulated,
       l.{{FinDepreciationLine.units}} AS units
FROM {{FinDepreciationLine}} l
JOIN {{FinDepreciationRun}} r ON r.{{FinDepreciationRun.runId}} = l.{{FinDepreciationLine.runId}}
WHERE r.{{FinDepreciationRun.status}} = 'POSTED'
  AND (CAST(:fromPeriod AS varchar) IS NULL OR l.{{FinDepreciationLine.periodKey}} >= :fromPeriod)
  AND (CAST(:toPeriod AS varchar) IS NULL OR l.{{FinDepreciationLine.periodKey}} <= :toPeriod)
  AND (CAST(:assetNo AS varchar) IS NULL OR l.{{FinDepreciationLine.assetNo}} = :assetNo)
UNION ALL
SELECT to_char(d.{{FinAssetDisposal.disposalDate}}, 'YYYY-MM'), d.{{FinAssetDisposal.assetNo}}, a.{{FinAsset.classCode}}, 'DISPOSAL',
       d.{{FinAssetDisposal.documentNo}}, d.{{FinAssetDisposal.monthDepreciation}}, d.{{FinAssetDisposal.accumulated}},
       NULL
FROM {{FinAssetDisposal}} d
JOIN {{FinAsset}} a ON a.{{FinAsset.assetId}} = d.{{FinAssetDisposal.assetId}}
WHERE d.{{FinAssetDisposal.monthDepreciation}} > 0
  AND (CAST(:fromPeriod AS varchar) IS NULL OR to_char(d.{{FinAssetDisposal.disposalDate}}, 'YYYY-MM') >= :fromPeriod)
  AND (CAST(:toPeriod AS varchar) IS NULL OR to_char(d.{{FinAssetDisposal.disposalDate}}, 'YYYY-MM') <= :toPeriod)
  AND (CAST(:assetNo AS varchar) IS NULL OR d.{{FinAssetDisposal.assetNo}} = :assetNo)
