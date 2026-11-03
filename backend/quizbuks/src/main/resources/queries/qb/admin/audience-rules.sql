/*---
id: qb.admin.audience-rules
description: "The managed audience rules (Q47c): whether sponsors may use each kind and whether it is charged for; a kind without a row is open and charged for"
entities: [QbAudienceRule]
datasets:
  QbAudienceRule: urn:jabiz:dataset:default:QbAudienceRule
results:
  ruleKey:   { from: QbAudienceRule.ruleKey }
  enabled:   { from: QbAudienceRule.enabled }
  billable:  { from: QbAudienceRule.billable }
  sortOrder: { from: QbAudienceRule.sortOrder }
  updatedAt: { from: QbAudienceRule.updatedAt }
  updatedBy: { from: QbAudienceRule.updatedBy }
list:
  sorts: [sortOrder, ruleKey]
  defaultSort: { field: sortOrder, asc: true }
  key: [ruleKey]
permissions: [qb.audience.rules.write]
---*/
SELECT
    r.{{QbAudienceRule.ruleKey}}   AS ruleKey,
    r.{{QbAudienceRule.enabled}}   AS enabled,
    r.{{QbAudienceRule.billable}}  AS billable,
    r.{{QbAudienceRule.sortOrder}} AS sortOrder,
    r.{{QbAudienceRule.updatedAt}} AS updatedAt,
    r.{{QbAudienceRule.updatedBy}} AS updatedBy
FROM {{QbAudienceRule}} r
