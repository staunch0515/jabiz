/*---
id: qb.system.package-imports-expired
description: "F-C4: the records of sponsor packages' imports not changed for qb.package.import-retention-days: the items of the daily job qb.package-import-expire"
entities: [QbPackageImport, SysParam]
datasets:
  QbPackageImport: urn:jabiz:dataset:default:QbPackageImport
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The scheduled time of the run }
results:
  importId:  { from: QbPackageImport.importId }
  updatedAt: { from: QbPackageImport.updatedAt }
list:
  sorts: [updatedAt]
  defaultSort: { field: updatedAt, asc: true }
  key: [importId]
permissions: [qb.package.system]
---*/
-- Without the parameter (QB_SETUP not run yet) its declared value, 90 days. QB_PACKAGE_IMPORT_EXPIRE reads the record
-- again under the Sponsor's lock and deletes it only when it is still past the retention.
WITH p AS (
    SELECT coalesce((SELECT CAST(max(v.{{SysParam.value}}) AS int) FROM {{SysParam}} v
                     WHERE v.{{SysParam.paramKey}} = 'qb.package.import-retention-days'
                       AND v.{{SysParam.value}} ~ '^[0-9]{1,4}$'), 90) AS days
)
SELECT i.{{QbPackageImport.importId}} AS importId, i.{{QbPackageImport.updatedAt}} AS updatedAt
FROM {{QbPackageImport}} i
CROSS JOIN p
WHERE i.{{QbPackageImport.updatedAt}} <= CAST(:at AS timestamptz) - make_interval(days => p.days)
