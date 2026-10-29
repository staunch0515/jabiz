/*---
id: qb.system.prospect-follow-up-due
description: The prospecting mails sent before a time that brought no sponsor and had no follow-up, oldest first (the items of the job qb.prospect-follow-up)
entities: [QbProspect, QbProspectSetting]
datasets:
  QbProspect: urn:jabiz:dataset:default:QbProspect
  QbProspectSetting: urn:jabiz:dataset:default:QbProspectSetting
params:
  sentBefore: { like: QbProspect.sentAt, required: true, description: "Mails sent before this have their follow-up due" }
results:
  prospectId: { from: QbProspect.prospectId }
  owner:      { from: QbProspect.owner }
  sentAt:     { from: QbProspect.sentAt }
list:
  sorts: [sentAt]
  defaultSort: { field: sentAt, asc: true }
  key: [prospectId]
permissions: [qb.prospect.system]
---*/
-- Through the partial index on the sent mails without a follow-up or a sponsor (V40). The item decides under the
-- owner's lock (the working hours and the day's cap, which first mails and follow-ups share).
SELECT p.{{QbProspect.prospectId}} AS prospectId, p.{{QbProspect.owner}} AS owner, p.{{QbProspect.sentAt}} AS sentAt
FROM {{QbProspect}} p
JOIN {{QbProspectSetting}} s ON s.{{QbProspectSetting.owner}} = p.{{QbProspect.owner}}
WHERE p.{{QbProspect.status}} = 'SENT'
  AND p.{{QbProspect.followedUpAt}} IS NULL
  AND p.{{QbProspect.convertedAt}} IS NULL
  AND p.{{QbProspect.sentAt}} < :sentBefore
  AND s.{{QbProspectSetting.enabled}}
