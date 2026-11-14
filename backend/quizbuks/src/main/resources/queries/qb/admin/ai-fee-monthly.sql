/*---
id: qb.admin.ai-fee-monthly
description: >-
  F-M9a-2 (#692 re-review M1): the charged AI jobs by the month they ended in (fee_period, the fiscal year's zone): how
  many, the fees charged to the sponsors and what the platform took on (absorbed: the overrun beyond a reservation, or
  what the credit could not hold), both in yen before tax with the markup in them. Not from the ledger: what the
  platform takes on is never posted; the month's charges are in qb.admin.monthly (AI_CHARGED)
entities: [QbAiJob]
datasets:
  QbAiJob: urn:jabiz:dataset:default:QbAiJob
params:
  month: { like: QbAiJob.feePeriod, description: "If given, only this month (yyyy-MM)" }
results:
  month:       { from: QbAiJob.feePeriod }
  jobs:        { kind: { type: numeric, precision: 12, scale: 0 } }
  feeYen:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  absorbedYen: { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [month]
  defaultSort: { field: month, asc: false }
  key: [month]
permissions: [qb.admin.finance.read]
---*/
-- The finished charged jobs (a job running has no month yet); one row a month.
SELECT j.{{QbAiJob.feePeriod}} AS month,
       CAST(count(*) AS numeric(12, 0)) AS jobs,
       CAST(coalesce(sum(j.{{QbAiJob.feeYen}}), 0) AS numeric(19, 0)) AS feeYen,
       CAST(coalesce(sum(j.{{QbAiJob.absorbedYen}}), 0) AS numeric(19, 0)) AS absorbedYen
FROM {{QbAiJob}} j
WHERE j.{{QbAiJob.feeMode}} = 'CREDIT' AND j.{{QbAiJob.feePeriod}} IS NOT NULL
  AND (CAST(:month AS text) IS NULL OR j.{{QbAiJob.feePeriod}} = :month)
GROUP BY j.{{QbAiJob.feePeriod}}
