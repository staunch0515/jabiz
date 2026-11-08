/*---
id: qb.system.tree-review-remind-due
description: "F-S2b2: the knowledge maps' versions waiting for their review longer than qb.tree.review.remind-after days and not reminded yet: the items of the daily job qb.tree-review-remind"
entities: [QbTreeVersion, SysParam]
datasets:
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The scheduled time of the run }
results:
  versionId: { from: QbTreeVersion.versionId }
  treeId:    { from: QbTreeVersion.treeId }
list:
  sorts: [versionId]
  defaultSort: { field: versionId, asc: true }
  key: [versionId]
permissions: [qb.tree.system]
---*/
-- Without the parameter (QB_SETUP not run yet) nobody is reminded. QB_TREE_REVIEW_REMIND reads the version again
-- under the map's lock and reminds once.
WITH p AS (
    SELECT (SELECT CAST(max(v.{{SysParam.value}}) AS int) FROM {{SysParam}} v
            WHERE v.{{SysParam.paramKey}} = 'qb.tree.review.remind-after'
              AND v.{{SysParam.value}} ~ '^[0-9]{1,4}$') AS days
)
SELECT v.{{QbTreeVersion.versionId}} AS versionId, v.{{QbTreeVersion.treeId}} AS treeId
FROM {{QbTreeVersion}} v
CROSS JOIN p
WHERE v.{{QbTreeVersion.status}} = 'REVIEW'
  AND v.{{QbTreeVersion.remindedAt}} IS NULL
  AND p.days IS NOT NULL
  AND v.{{QbTreeVersion.submittedAt}} <= CAST(:at AS timestamptz) - make_interval(days => p.days)
