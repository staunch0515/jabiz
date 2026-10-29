/*---
id: qb.system.prospect-research-due
description: The businesses found and not yet researched, of owners that prospect, oldest first (the items of the job qb.prospect-research)
entities: [QbProspect, QbProspectSetting]
datasets:
  QbProspect: urn:jabiz:dataset:default:QbProspect
  QbProspectSetting: urn:jabiz:dataset:default:QbProspectSetting
params:
  dayStart: { like: QbProspect.foundAt, required: true, description: The start of the day (the deployment's zone) }
results:
  prospectId: { from: QbProspect.prospectId }
  owner:      { from: QbProspect.owner }
  foundAt:    { from: QbProspect.foundAt }
list:
  sorts: [foundAt]
  defaultSort: { field: foundAt, asc: true }
  key: [prospectId]
permissions: [qb.prospect.system]
---*/
-- Each owner's oldest finds, as many as it may still research today (twice its daily cap of mails, less what it began
-- today), so that the job's items are not spent on owners that have none left. The item counts again under the
-- owner's lock: this only orders and trims.
SELECT p.prospectId, p.owner, p.foundAt
FROM (
    SELECT f.{{QbProspect.prospectId}} AS prospectId, f.{{QbProspect.owner}} AS owner,
           f.{{QbProspect.foundAt}} AS foundAt,
           row_number() OVER (PARTITION BY f.{{QbProspect.owner}} ORDER BY f.{{QbProspect.foundAt}},
               f.{{QbProspect.prospectId}}) AS n,
           s.{{QbProspectSetting.dailyCap}} * 2 - (
               SELECT count(*) FROM {{QbProspect}} t
                WHERE t.{{QbProspect.owner}} = f.{{QbProspect.owner}}
                  AND t.{{QbProspect.researchStartedAt}} >= :dayStart) AS remaining
    FROM {{QbProspect}} f
    JOIN {{QbProspectSetting}} s ON s.{{QbProspectSetting.owner}} = f.{{QbProspect.owner}}
    WHERE f.{{QbProspect.status}} = 'FOUND'
      AND s.{{QbProspectSetting.enabled}}
) p
WHERE p.n <= p.remaining
