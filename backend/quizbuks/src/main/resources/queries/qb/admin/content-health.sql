/*---
id: qb.admin.content-health
description: "F-S6: what is wrong with the quizzes' content, one row a kind of problem: versions whose snapshot is near the size limit (a bank that grows further will not save its next version); empty when all is well"
entities: [QbQuizVersion]
datasets:
  QbQuizVersion: urn:jabiz:dataset:default:QbQuizVersion
params:
  nearBytes: { kind: { type: numeric, precision: 12, scale: 0 }, required: true, description: "The size in bytes over which a snapshot is near the limit (content.ContentHealth.nearBytes)" }
results:
  checkCode: { kind: { type: text, maxLength: 30 } }
  found:     { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [checkCode]
  defaultSort: { field: checkCode, asc: true }
  key: [checkCode]
permissions: [qb.admin.quiz.read]
---*/
-- One statement. The daily job QB_CONTENT_CHECK_RUN asks the platform with a task when it returns anything.
-- SNAPSHOT_NEAR_LIMIT: versions whose canonical snapshot is over the share of ContentLimits.MAX_SNAPSHOT_BYTES given
-- (octet_length reads a stored value's size without decompressing it).
SELECT CAST('SNAPSHOT_NEAR_LIMIT' AS varchar(30)) AS checkCode, CAST(count(*) AS numeric) AS found
FROM {{QbQuizVersion}} v
WHERE octet_length(v.{{QbQuizVersion.content}}) > :nearBytes
HAVING count(*) > 0
