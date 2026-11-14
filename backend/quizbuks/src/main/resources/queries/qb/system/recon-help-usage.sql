/*---
id: qb.system.recon-help-usage
description: >-
  F-M10: the months in which everybody's row of the AI help's usage (user "*") is below the sum of the people's rows in
  questions, input tokens, output tokens or estimated dollars (the daily reconciliation; differences only). Not "equal":
  deleting an account erases the person's row and keeps everybody's, and a conversation already deleted adds only to
  everybody's, so the total may be more than the people's sum, never less. F-M9 (#692 re-review, a note): the charged AI
  jobs whose fee went beyond what they could take (their reservation, or the credit), the rest taken on by the platform,
  until the month's charge takes them in, and at most in the month they ended and the month after (a job charged
  nothing is never taken in by a charge: the a-2 review M1; #699 re-review L1);
  the reference is the job, the actual what the platform took on before tax
entities: [QbHelpUsage, QbAiJob]
datasets:
  QbHelpUsage: urn:jabiz:dataset:default:QbHelpUsage
  QbAiJob: urn:jabiz:dataset:default:QbAiJob
params:
  at:   { like: QbAiJob.finishedAt, description: "The run's time (none: the AI jobs are not listed)" }
  zone: { kind: { type: text, maxLength: 64 }, description: "The fiscal year's zone, whose months the AI fees are kept by (none: the AI jobs are not listed)" }
results:
  checkCode: { kind: { type: text, maxLength: 30 } }
  reference: { kind: { type: text, maxLength: 100 } }
  expected:  { kind: { type: numeric, precision: 20, scale: 0 } }
  actual:    { kind: { type: numeric, precision: 20, scale: 0 } }
list:
  sorts: [reference]
  defaultSort: { field: reference, asc: true }
  key: [reference]
permissions: [qb.wallet.system]
---*/
-- One statement, one snapshot. The reference names the month and what was counted: 2026-10:questions. The items hold
-- whole numbers (QbReconItem is in yen): the dollars are given in millionths of a dollar (costMicros), the unit the
-- amounts are kept in, and compared before they are rounded, so a total that is not below the sum is never reported.
WITH months AS (
    SELECT u.{{QbHelpUsage.month}} AS month,
           sum(u.{{QbHelpUsage.questions}})    FILTER (WHERE u.{{QbHelpUsage.userId}} <> '*') AS pQuestions,
           sum(u.{{QbHelpUsage.inputTokens}})  FILTER (WHERE u.{{QbHelpUsage.userId}} <> '*') AS pInput,
           sum(u.{{QbHelpUsage.outputTokens}}) FILTER (WHERE u.{{QbHelpUsage.userId}} <> '*') AS pOutput,
           sum(u.{{QbHelpUsage.costUsd}})      FILTER (WHERE u.{{QbHelpUsage.userId}} <> '*') AS pCost,
           coalesce(sum(u.{{QbHelpUsage.questions}})    FILTER (WHERE u.{{QbHelpUsage.userId}} = '*'), 0) AS tQuestions,
           coalesce(sum(u.{{QbHelpUsage.inputTokens}})  FILTER (WHERE u.{{QbHelpUsage.userId}} = '*'), 0) AS tInput,
           coalesce(sum(u.{{QbHelpUsage.outputTokens}}) FILTER (WHERE u.{{QbHelpUsage.userId}} = '*'), 0) AS tOutput,
           coalesce(sum(u.{{QbHelpUsage.costUsd}})      FILTER (WHERE u.{{QbHelpUsage.userId}} = '*'), 0) AS tCost
    FROM {{QbHelpUsage}} u
    GROUP BY u.{{QbHelpUsage.month}})
SELECT CAST('HELP_USAGE_TOTAL' AS varchar(30)) AS checkCode, CAST(m.month || ':questions' AS varchar(100)) AS reference,
       CAST(m.pQuestions AS numeric(20, 0)) AS expected, CAST(m.tQuestions AS numeric(20, 0)) AS actual
FROM months m WHERE m.tQuestions < coalesce(m.pQuestions, 0)
UNION ALL
SELECT 'HELP_USAGE_TOTAL', m.month || ':inputTokens', CAST(m.pInput AS numeric(20, 0)), CAST(m.tInput AS numeric(20, 0))
FROM months m WHERE m.tInput < coalesce(m.pInput, 0)
UNION ALL
SELECT 'HELP_USAGE_TOTAL', m.month || ':outputTokens', CAST(m.pOutput AS numeric(20, 0)), CAST(m.tOutput AS numeric(20, 0))
FROM months m WHERE m.tOutput < coalesce(m.pOutput, 0)
UNION ALL
SELECT 'HELP_USAGE_TOTAL', m.month || ':costMicros', CAST(round(m.pCost * 1000000) AS numeric(20, 0)), CAST(round(m.tCost * 1000000) AS numeric(20, 0))
FROM months m WHERE m.tCost < coalesce(m.pCost, 0)
UNION ALL
SELECT 'AI_RESERVATION_SHORT', CAST(j.{{QbAiJob.aiJobId}} AS varchar(100)), CAST(0 AS numeric(20, 0)),
       CAST(j.{{QbAiJob.absorbedYen}} AS numeric(20, 0))
FROM {{QbAiJob}} j
WHERE j.{{QbAiJob.feeMode}} = 'CREDIT' AND j.{{QbAiJob.feeChargeId}} IS NULL AND j.{{QbAiJob.absorbedYen}} > 0
  -- The month before :at in :zone and after (fee_period is the month the job ended in, in the same zone).
  AND j.{{QbAiJob.feePeriod}} >= to_char(date_trunc('month', CAST(:at AS timestamptz) AT TIME ZONE CAST(:zone AS text))
                                         - interval '1 month', 'YYYY-MM')
