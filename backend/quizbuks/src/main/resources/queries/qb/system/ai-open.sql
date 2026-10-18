/*---
id: qb.system.ai-open
description: >-
  What every sponsor's unfinished AI jobs may still use (Q8a, A-7): read by QB_AI_QUESTIONS_REQUEST under qb.ai:*,
  so that the global monthly limit counts the jobs already running, not only what they have recorded
entities: [QbAiJob]
datasets:
  QbAiJob: urn:jabiz:dataset:default:QbAiJob
results:
  openJobs:     { kind: { type: numeric, precision: 9, scale: 0 } }
  openEstimate: { kind: { type: numeric, precision: 15, scale: 0 } }
permissions: [qb.ai.system]
---*/
-- One row, through the partial index on the unfinished jobs (qb_ai_job_open_idx): a job is unfinished until its
-- outcome is recorded (finished_at), a cancelled one too.
SELECT
    CAST(count(*) AS numeric(9, 0))                                    AS openJobs,
    CAST(coalesce(sum(j.{{QbAiJob.estimatedTokens}}), 0) AS numeric(15, 0)) AS openEstimate
FROM {{QbAiJob}} j
WHERE j.{{QbAiJob.finishedAt}} IS NULL
