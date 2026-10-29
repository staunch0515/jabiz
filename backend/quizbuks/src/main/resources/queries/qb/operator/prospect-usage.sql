/*---
id: qb.operator.prospect-usage
description: "What prospecting cost the caller operator by month (Q48c): tokens, searches and the estimate in US dollars"
entities: [QbProspectUsage, QbOperatorMember]
datasets:
  QbProspectUsage: urn:jabiz:dataset:default:QbProspectUsage
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
results:
  month:        { from: QbProspectUsage.month }
  inputTokens:  { from: QbProspectUsage.inputTokens }
  outputTokens: { from: QbProspectUsage.outputTokens }
  searches:     { from: QbProspectUsage.searches }
  jobs:         { from: QbProspectUsage.jobs }
  costUsd:      { from: QbProspectUsage.costUsd }
list:
  sorts: [month]
  defaultSort: { field: month, asc: false }
  key: [month]
permissions: [qb.prospect.use]
---*/
SELECT u.{{QbProspectUsage.month}} AS month, u.{{QbProspectUsage.inputTokens}} AS inputTokens,
       u.{{QbProspectUsage.outputTokens}} AS outputTokens, u.{{QbProspectUsage.searches}} AS searches,
       u.{{QbProspectUsage.jobs}} AS jobs, u.{{QbProspectUsage.costUsd}} AS costUsd
FROM {{QbProspectUsage}} u
JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
  AND CAST(me.{{QbOperatorMember.operatorId}} AS text) = u.{{QbProspectUsage.owner}}
