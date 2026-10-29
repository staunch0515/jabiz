/*---
id: qb.admin.prospect-usage
description: "What prospecting cost an owner by month (Q48c): tokens, searches and the estimate in US dollars"
entities: [QbProspectUsage]
datasets:
  QbProspectUsage: urn:jabiz:dataset:default:QbProspectUsage
params:
  owner: { like: QbProspectUsage.owner, required: true, description: "The owner: an operator's id, or direct (headquarters)" }
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
permissions: [qb.admin.prospect]
---*/
SELECT u.{{QbProspectUsage.month}} AS month, u.{{QbProspectUsage.inputTokens}} AS inputTokens,
       u.{{QbProspectUsage.outputTokens}} AS outputTokens, u.{{QbProspectUsage.searches}} AS searches,
       u.{{QbProspectUsage.jobs}} AS jobs, u.{{QbProspectUsage.costUsd}} AS costUsd
FROM {{QbProspectUsage}} u
WHERE u.{{QbProspectUsage.owner}} = :owner
