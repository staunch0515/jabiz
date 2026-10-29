/*---
id: qb.system.prospect-find-due
description: The active directions of owners that prospect, not searched since the day began (the items of the job qb.prospect-find)
entities: [QbProspectDirection, QbProspectSetting]
datasets:
  QbProspectDirection: urn:jabiz:dataset:default:QbProspectDirection
  QbProspectSetting: urn:jabiz:dataset:default:QbProspectSetting
params:
  dayStart: { like: QbProspectDirection.lastSearchAt, required: true, description: The start of the day (the deployment's zone) }
results:
  directionId: { from: QbProspectDirection.directionId }
  owner:       { from: QbProspectDirection.owner }
list:
  sorts: [directionId]
  defaultSort: { field: directionId, asc: true }
  key: [directionId]
permissions: [qb.prospect.system]
---*/
-- Each item's process reads the direction, the settings and the month's cost again under the owner's lock and starts
-- nothing when they no longer allow it; this only leaves out what surely has nothing to do.
SELECT d.{{QbProspectDirection.directionId}} AS directionId, d.{{QbProspectDirection.owner}} AS owner
FROM {{QbProspectDirection}} d
JOIN {{QbProspectSetting}} s ON s.{{QbProspectSetting.owner}} = d.{{QbProspectDirection.owner}}
WHERE d.{{QbProspectDirection.active}}
  AND s.{{QbProspectSetting.enabled}}
  AND (d.{{QbProspectDirection.lastSearchAt}} IS NULL OR d.{{QbProspectDirection.lastSearchAt}} < :dayStart)
