/*---
id: qb.admin.ai-fees
description: >-
  F-M9: the AI fees by month, sponsor and the operator the fee was frozen with - jobs, the dollars they cost, the fee
  charged, what the platform took on (the overrun and credit that fell short as absorbedYen; the calls with no answer
  as estimated dollars, shown in yen at the rate frozen on each job, so both are in absorbedTotalYen), the fees not
  charged yet, and the sponsor's AI credit now. Exportable (POST /api/queries/{id}/export)
entities: [QbAiJob, QbSponsor, QbOperator, QbPrepayment]
datasets:
  QbAiJob: urn:jabiz:dataset:default:QbAiJob
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbOperator: urn:jabiz:dataset:default:QbOperator
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
params:
  month:      { like: QbAiJob.feePeriod, description: "If given, only this fee month (yyyy-MM)" }
  sponsorId:  { like: QbAiJob.sponsorId, description: "If given, only this sponsor" }
  operatorId: { like: QbAiJob.feeOperatorId, description: "If given, only the jobs whose fee was frozen with this operator" }
results:
  month:            { from: QbAiJob.feePeriod }
  sponsorId:        { from: QbAiJob.sponsorId }
  company:          { from: QbSponsor.company }
  operatorId:       { from: QbAiJob.feeOperatorId }
  operatorName:     { from: QbOperator.name }
  jobs:             { kind: { type: numeric, precision: 12, scale: 0 } }
  costUsd:          { kind: { type: numeric, precision: 16, scale: 6 } }
  feeYen:           { kind: { type: monetary, currency: JPY, scale: 0 } }
  absorbedYen:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  unrecordedUsd:    { kind: { type: numeric, precision: 16, scale: 6 } }
  absorbedTotalYen: { kind: { type: monetary, currency: JPY, scale: 0 } }
  unbilledYen:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  aiCredit:         { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [month, company, jobs, feeYen, absorbedTotalYen, unbilledYen]
  defaultSort: { field: month, asc: false }
  key: [month, sponsorId, operatorId]
permissions: [qb.admin.ai.read]
---*/
-- The jobs whose result was recorded in a month (they carry feePeriod); a job still running has none. absorbedYen is
-- before tax and has the markup in it; unrecordedUsd is a cost, brought to yen by the rate frozen on the job (rounded
-- up, as the fee is) and with no markup: the two are told apart in the columns and added in absorbedTotalYen, which is
-- what qb.system.ai-absorbed alerts on. The jobs with no operator (the platform's own sponsors) are rows of their own.
-- The credit is the sponsor's open AI prepayments now, whatever the month.
WITH g AS (
    SELECT j.{{QbAiJob.feePeriod}}     AS g_month,
           j.{{QbAiJob.sponsorId}}     AS g_sponsor,
           j.{{QbAiJob.feeOperatorId}} AS g_operator,
           count(*)                    AS g_jobs,
           sum(j.{{QbAiJob.costUsd}})  AS g_cost,
           sum(j.{{QbAiJob.feeYen}})   AS g_fee,
           sum(j.{{QbAiJob.absorbedYen}}) AS g_absorbed,
           sum(j.{{QbAiJob.unrecordedUsd}}) AS g_unrecorded,
           sum(coalesce(j.{{QbAiJob.absorbedYen}}, 0)
               + ceil(coalesce(j.{{QbAiJob.unrecordedUsd}}, 0) * coalesce(j.{{QbAiJob.feeUsdJpy}}, 0))) AS g_total,
           sum(j.{{QbAiJob.feeYen}}) FILTER (WHERE j.{{QbAiJob.feeChargeId}} IS NULL) AS g_unbilled
    FROM {{QbAiJob}} j
    WHERE j.{{QbAiJob.feePeriod}} IS NOT NULL
      AND (CAST(:month AS text) IS NULL OR j.{{QbAiJob.feePeriod}} = :month)
      AND (CAST(:sponsorId AS text) IS NULL OR j.{{QbAiJob.sponsorId}} = :sponsorId)
      AND (CAST(:operatorId AS text) IS NULL OR j.{{QbAiJob.feeOperatorId}} = :operatorId)
    GROUP BY j.{{QbAiJob.feePeriod}}, j.{{QbAiJob.sponsorId}}, j.{{QbAiJob.feeOperatorId}}
)
SELECT
    g.g_month    AS month,
    g.g_sponsor  AS sponsorId,
    s.{{QbSponsor.company}} AS company,
    g.g_operator AS operatorId,
    o.{{QbOperator.name}}   AS operatorName,
    CAST(g.g_jobs AS numeric(12, 0))                      AS jobs,
    CAST(coalesce(g.g_cost, 0) AS numeric(16, 6))         AS costUsd,
    CAST(coalesce(g.g_fee, 0) AS numeric(19, 0))          AS feeYen,
    CAST(coalesce(g.g_absorbed, 0) AS numeric(19, 0))     AS absorbedYen,
    CAST(coalesce(g.g_unrecorded, 0) AS numeric(16, 6))   AS unrecordedUsd,
    CAST(coalesce(g.g_total, 0) AS numeric(19, 0))        AS absorbedTotalYen,
    CAST(coalesce(g.g_unbilled, 0) AS numeric(19, 0))     AS unbilledYen,
    CAST(coalesce(c.c_remaining, 0) AS numeric(19, 0))    AS aiCredit
FROM g
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = g.g_sponsor
LEFT JOIN {{QbOperator}} o ON CAST(o.{{QbOperator.operatorId}} AS text) = g.g_operator
LEFT JOIN LATERAL (
    SELECT sum(p.{{QbPrepayment.remaining}}) AS c_remaining
    FROM {{QbPrepayment}} p
    WHERE p.{{QbPrepayment.sponsorId}} = g.g_sponsor
      AND p.{{QbPrepayment.purpose}} = 'AI' AND p.{{QbPrepayment.status}} = 'OPEN') c ON true
