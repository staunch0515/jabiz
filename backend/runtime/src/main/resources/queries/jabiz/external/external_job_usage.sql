/*---
id: jabiz.external_job_usage
description: >-
  What external jobs consumed (decision D38 item 4): the usage their attempts recorded, failed attempts included,
  summed by job type, metric and the month (UTC) the attempts ended in, with the number of attempts that reported the
  metric. For the application's own statistics; the platform does no billing.
entities: [ExternalJob, ExternalJobAttempt, ExternalJobUsage]
params:
  from:    { like: ExternalJobAttempt.finishedTime, description: "if given, only attempts that ended at or after this time count" }
  to:      { like: ExternalJobAttempt.finishedTime, description: "if given, only attempts that ended before this time count" }
  jobType: { like: ExternalJob.jobType, description: "if given, only jobs of this type" }
results:
  jobType:  { from: ExternalJob.jobType }
  metric:   { from: ExternalJobUsage.metric }
  month:    { kind: { type: text, maxLength: 7 } }
  attempts: { kind: { type: numeric, precision: 18, scale: 0 } }
  quantity: { kind: { type: numeric, precision: 19, scale: 0 } }
list:
  filters: [jobType, metric, month]
  sorts:   [jobType, metric, month, quantity]
  defaultSort: { field: month, asc: false }
  key: [jobType, metric, month]
permissions: [external-job.read]
report:
  period: { from: from, to: to }
---*/
SELECT
    j.{{ExternalJob.jobType}} AS jobType,
    u.{{ExternalJobUsage.metric}} AS metric,
    to_char(a.{{ExternalJobAttempt.finishedTime}} AT TIME ZONE 'UTC', 'YYYY-MM') AS month,
    COUNT(*) AS attempts,
    SUM(u.{{ExternalJobUsage.quantity}}) AS quantity
FROM {{ExternalJobUsage}} u
JOIN {{ExternalJobAttempt}} a
  ON a.{{ExternalJobAttempt.jobId}} = u.{{ExternalJobUsage.jobId}}
 AND a.{{ExternalJobAttempt.attemptNo}} = u.{{ExternalJobUsage.attemptNo}}
JOIN {{ExternalJob}} j ON j.{{ExternalJob.jobId}} = u.{{ExternalJobUsage.jobId}}
WHERE (CAST(:from AS timestamptz) IS NULL OR a.{{ExternalJobAttempt.finishedTime}} >= :from)
  AND (CAST(:to AS timestamptz) IS NULL OR a.{{ExternalJobAttempt.finishedTime}} < :to)
  AND (CAST(:jobType AS varchar) IS NULL OR j.{{ExternalJob.jobType}} = :jobType)
GROUP BY j.{{ExternalJob.jobType}}, u.{{ExternalJobUsage.metric}},
    to_char(a.{{ExternalJobAttempt.finishedTime}} AT TIME ZONE 'UTC', 'YYYY-MM')
