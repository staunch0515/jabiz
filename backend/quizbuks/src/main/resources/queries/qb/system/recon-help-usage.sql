/*---
id: qb.system.recon-help-usage
description: >-
  F-M10: the months in which everybody's row of the AI help's usage (user "*") is below the sum of the people's rows in
  questions, input tokens, output tokens or estimated dollars (the daily reconciliation; differences only). Not "equal":
  deleting an account erases the person's row and keeps everybody's, and a conversation already deleted adds only to
  everybody's, so the total may be more than the people's sum, never less
entities: [QbHelpUsage]
datasets:
  QbHelpUsage: urn:jabiz:dataset:default:QbHelpUsage
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
-- whole numbers (QbReconItem is in yen): the dollars are compared and given in cents (costCents), each total and sum
-- rounded the same way, so a total that is not below the sum is never below it rounded.
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
SELECT 'HELP_USAGE_TOTAL', m.month || ':costCents', CAST(round(m.pCost * 100) AS numeric(20, 0)), CAST(round(m.tCost * 100) AS numeric(20, 0))
FROM months m WHERE round(m.tCost * 100) < round(coalesce(m.pCost, 0) * 100)
