/*---
id: qb.system.prospect-send-due
description: The approved prospecting mails, oldest approval first (the items of the job qb.prospect-send)
entities: [QbProspect, QbProspectSetting]
datasets:
  QbProspect: urn:jabiz:dataset:default:QbProspect
  QbProspectSetting: urn:jabiz:dataset:default:QbProspectSetting
params:
  dayStart: { like: QbProspect.sentAt, required: true, description: The start of the day (the deployment's zone) }
results:
  prospectId: { from: QbProspect.prospectId }
  owner:      { from: QbProspect.owner }
  approvedAt: { from: QbProspect.approvedAt }
list:
  sorts: [approvedAt]
  defaultSort: { field: approvedAt, asc: true }
  key: [prospectId]
permissions: [qb.prospect.system]
---*/
-- Each owner's oldest approvals, as many as its daily cap still allows today. The item decides under the owner's lock
-- (the working hours, the cap, blocks, unsubscriptions and the mail server): this only orders and trims.
SELECT p.prospectId, p.owner, p.approvedAt
FROM (
    SELECT a.{{QbProspect.prospectId}} AS prospectId, a.{{QbProspect.owner}} AS owner,
           a.{{QbProspect.approvedAt}} AS approvedAt,
           row_number() OVER (PARTITION BY a.{{QbProspect.owner}} ORDER BY a.{{QbProspect.approvedAt}},
               a.{{QbProspect.prospectId}}) AS n,
           s.{{QbProspectSetting.dailyCap}} - (
               SELECT count(*) FROM {{QbProspect}} t
                WHERE t.{{QbProspect.owner}} = a.{{QbProspect.owner}}
                  AND t.{{QbProspect.sentAt}} >= :dayStart) AS remaining
    FROM {{QbProspect}} a
    JOIN {{QbProspectSetting}} s ON s.{{QbProspectSetting.owner}} = a.{{QbProspect.owner}}
    WHERE a.{{QbProspect.status}} = 'APPROVED'
      AND s.{{QbProspectSetting.enabled}}
) p
WHERE p.n <= p.remaining
