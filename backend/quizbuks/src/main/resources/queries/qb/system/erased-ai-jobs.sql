/*---
id: qb.system.erased-ai-jobs
description: "An erased sponsor's AI jobs that may still be open (F-M15c, plan §5.5): their platform jobs of the given types (QuizBuks' own job rows are deleted with the account), less those that ended by an attempt (succeeded, failed for good, or the type's last attempt made)"
entities: [ExternalJob, ExternalJobAttempt]
datasets:
  ExternalJob: urn:jabiz:dataset:platform:ExternalJob
  ExternalJobAttempt: urn:jabiz:dataset:platform:ExternalJobAttempt
params:
  userId: { like: ExternalJob.requestedBy, required: true, description: The erased sponsor }
  types:  { like: ExternalJob.jobType, list: true, required: true, description: The AI job types a sponsor asks for }
  limits: { kind: { type: text, maxLength: 120 }, list: true, required: true, description: "Each type's attempts, as type:attempts" }
  max:    { kind: { type: numeric, precision: 9, scale: 0 }, required: true, description: The most jobs read at once }
results:
  jobId:    { kind: { type: text, maxLength: 36 } }
  jobType:  { from: ExternalJob.jobType }
  total:    { kind: { type: numeric, precision: 19, scale: 0 } }
list:
  sorts: [jobId]
  defaultSort: { field: jobId, asc: true }
  key: [jobId]
permissions: [qb.ai.system]
---*/
-- The platform purge keeps requested_by (it overwrites the input and the result only), so the jobs are still found by
-- the erased sponsor after it. Whether a job is open is the platform's to say (its cancellations and outcomes are not
-- datasets): this leaves out what an attempt ended, and those whose attempts are used up (limits: each type's retry);
-- the cancellation (ifOpen) does nothing to one that ended otherwise (cancelled before). No time window: a job of a type
-- held by its switch (enabledBy) waits without using attempts, however long. total is how many there are, read or
-- not: more than max is reported by the wind-up, which waits for the next run.
SELECT x.jobId, x.jobType, x.total
FROM (
    SELECT CAST(j.{{ExternalJob.jobId}} AS text) AS jobId, j.{{ExternalJob.jobType}} AS jobType,
           count(*) OVER () AS total
    FROM {{ExternalJob}} j
    LEFT JOIN {{ExternalJobAttempt}} a ON a.{{ExternalJobAttempt.jobId}} = j.{{ExternalJob.jobId}}
    WHERE j.{{ExternalJob.requestedBy}} = :userId
      AND j.{{ExternalJob.jobType}} = ANY(:types)
    GROUP BY j.{{ExternalJob.jobId}}, j.{{ExternalJob.jobType}}, j.{{ExternalJob.jobSeq}}
    HAVING NOT coalesce(bool_or(a.{{ExternalJobAttempt.outcome}} = 'SUCCEEDED'
                                OR NOT a.{{ExternalJobAttempt.retryable}}), false)
       AND count(a.{{ExternalJobAttempt.attemptId}})
           < (SELECT max(CAST(split_part(l, ':', 2) AS int)) FROM unnest(CAST(:limits AS text[])) l
              WHERE split_part(l, ':', 1) = j.{{ExternalJob.jobType}})
    ORDER BY j.{{ExternalJob.jobSeq}} DESC
    LIMIT :max
) x
