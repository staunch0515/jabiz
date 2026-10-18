/*---
id: qb.sponsor.ai-usage
description: >-
  The sponsor's AI usage this month (Q8a, M-10): tokens in and out, jobs, today's requests, and what remains of the
  month's and the day's limits, with whether AI is on; months and days of the deployment's zone
entities: [QbAiUsage, QbAiJob, SysParam, QbStatsRun]
datasets:
  QbAiUsage: urn:jabiz:dataset:sponsor:QbAiUsage
  QbAiJob: urn:jabiz:dataset:sponsor:QbAiJob
  SysParam: urn:jabiz:dataset:now:SysParam
  QbStatsRun: urn:jabiz:dataset:default:QbStatsRun
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The client's now: this month and today are its month and day in the deployment's zone" }
results:
  month:           { kind: { type: text, maxLength: 7 } }
  enabled:         { kind: { type: bool } }
  jobs:            { kind: { type: numeric, precision: 9, scale: 0 } }
  failedJobs:      { kind: { type: numeric, precision: 9, scale: 0 } }
  inputTokens:     { kind: { type: numeric, precision: 15, scale: 0 } }
  outputTokens:    { kind: { type: numeric, precision: 15, scale: 0 } }
  monthlyTokens:   { kind: { type: numeric, precision: 15, scale: 0 } }
  reservedTokens:  { kind: { type: numeric, precision: 15, scale: 0 } }
  remainingTokens: { kind: { type: numeric, precision: 15, scale: 0 } }
  dailyJobs:       { kind: { type: numeric, precision: 9, scale: 0 } }
  jobsToday:       { kind: { type: numeric, precision: 9, scale: 0 } }
  monthEnds:       { kind: { type: temporal, role: EVENT_TIME } }
  dayEnds:         { kind: { type: temporal, role: EVENT_TIME } }
permissions: [qb.ai.use]
---*/
-- One row. The rules are QB_AI_QUESTIONS_REQUEST's (content.ai.AiLimits decides; this is for the page): the month's
-- tokens of finished jobs, plus what the unfinished ones may still use (their estimate; cancelled ones until the
-- provider is done with them), within qb.ai.sponsor.monthly-tokens; today's requests within qb.ai.sponsor.daily-jobs.
-- A parameter not set (or not a whole number) leaves its figures null, and AI is then off whatever the switch says.
-- The zone is the one the analytics job recorded (jabiz.fiscal-year-zone, D-Q9e-3), not written here.
WITH run AS (
    SELECT coalesce(max(r.{{QbStatsRun.zone}}), 'UTC') AS z
    FROM {{QbStatsRun}} r
    WHERE r.{{QbStatsRun.job}} = 'qb.analytics'),
clock AS (
    SELECT
        to_char(CAST(:at AS timestamptz) AT TIME ZONE run.z, 'YYYY-MM') AS v_month,
        (date_trunc('day', CAST(:at AS timestamptz) AT TIME ZONE run.z) AT TIME ZONE run.z) AS day_start,
        ((date_trunc('day', CAST(:at AS timestamptz) AT TIME ZONE run.z) + interval '1 day') AT TIME ZONE run.z)
            AS day_end,
        ((date_trunc('month', CAST(:at AS timestamptz) AT TIME ZONE run.z) + interval '1 month') AT TIME ZONE run.z)
            AS month_end
    FROM run),
params AS (
    SELECT
        max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.ai.enabled' THEN p.{{SysParam.value}} END) AS p_enabled,
        max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.ai.sponsor.monthly-tokens' THEN p.{{SysParam.value}} END)
            AS p_monthly,
        max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.ai.sponsor.daily-jobs' THEN p.{{SysParam.value}} END) AS p_daily
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} IN ('qb.ai.enabled', 'qb.ai.sponsor.monthly-tokens', 'qb.ai.sponsor.daily-jobs')),
checked AS (
    SELECT
        p_enabled = 'true' AS v_enabled,
        CASE WHEN p_monthly ~ '^[0-9]{1,15}$' THEN CAST(p_monthly AS numeric) END AS v_monthly,
        CASE WHEN p_daily ~ '^[0-9]{1,9}$' THEN CAST(p_daily AS numeric) END AS v_daily
    FROM params),
used AS (
    SELECT
        coalesce(sum(u.{{QbAiUsage.jobs}}), 0)         AS v_jobs,
        coalesce(sum(u.{{QbAiUsage.failedJobs}}), 0)   AS v_failed,
        coalesce(sum(u.{{QbAiUsage.inputTokens}}), 0)  AS v_in,
        coalesce(sum(u.{{QbAiUsage.outputTokens}}), 0) AS v_out
    FROM {{QbAiUsage}} u
    CROSS JOIN clock c
    WHERE u.{{QbAiUsage.month}} = c.v_month),
jobs AS (
    SELECT
        coalesce(sum(j.{{QbAiJob.estimatedTokens}}) FILTER (WHERE j.{{QbAiJob.status}} = 'QUEUED'
            OR (j.{{QbAiJob.status}} = 'CANCELLED' AND j.{{QbAiJob.finishedAt}} IS NULL)), 0) AS v_reserved,
        count(*) FILTER (WHERE j.{{QbAiJob.requestedAt}} >= c.day_start) AS v_today
    FROM {{QbAiJob}} j
    CROSS JOIN clock c
    WHERE j.{{QbAiJob.requestedAt}} >= c.day_start
       OR j.{{QbAiJob.status}} IN ('QUEUED', 'CANCELLED'))
SELECT
    CAST(c.v_month AS varchar(7)) AS month,
    coalesce(k.v_enabled, false) AND k.v_monthly IS NOT NULL AND k.v_daily IS NOT NULL AS enabled,
    u.v_jobs AS jobs,
    u.v_failed AS failedJobs,
    u.v_in AS inputTokens,
    u.v_out AS outputTokens,
    k.v_monthly AS monthlyTokens,
    j.v_reserved AS reservedTokens,
    CASE WHEN k.v_monthly IS NOT NULL THEN greatest(k.v_monthly - u.v_in - u.v_out - j.v_reserved, 0) END
        AS remainingTokens,
    k.v_daily AS dailyJobs,
    j.v_today AS jobsToday,
    c.month_end AS monthEnds,
    c.day_end AS dayEnds
FROM clock c
CROSS JOIN checked k
CROSS JOIN used u
CROSS JOIN jobs j
