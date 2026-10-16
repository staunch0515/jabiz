/*---
id: qb.system.attempts-due
description: Attempts still in progress whose deadline is before the cutoff, earliest first (the items of the job qb.attempt-expire)
entities: [QbAttempt]
datasets:
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
params:
  cutoff: { like: QbAttempt.deadline, required: true, description: The scheduled time less the grace of a submission }
results:
  attemptId:     { from: QbAttempt.attemptId }
  publicationId: { from: QbAttempt.publicationId }
  deadline:      { from: QbAttempt.deadline }
list:
  sorts: [deadline]
  defaultSort: { field: deadline, asc: true }
  key: [attemptId]
permissions: [qb.play.system]
---*/
-- Through the partial index on the deadline of the attempts in progress (V6). The job's process reads the attempt
-- again under the publication's lock and leaves one that was submitted meanwhile; the publication (immutable) is the
-- key of the queue before that lock (platform decision D45).
SELECT a.{{QbAttempt.attemptId}} AS attemptId, a.{{QbAttempt.publicationId}} AS publicationId,
       a.{{QbAttempt.deadline}} AS deadline
FROM {{QbAttempt}} a
WHERE a.{{QbAttempt.status}} = 'IN_PROGRESS'
  AND a.{{QbAttempt.deadline}} < :cutoff
