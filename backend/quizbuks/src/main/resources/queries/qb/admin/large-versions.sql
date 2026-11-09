/*---
id: qb.admin.large-versions
description: "F-S6: the quiz versions whose snapshot is over a size in bytes, largest first: which quizzes the daily check's SNAPSHOT_NEAR_LIMIT counted (qb.admin.content-health)"
entities: [QbQuizVersion]
datasets:
  QbQuizVersion: urn:jabiz:dataset:default:QbQuizVersion
params:
  overBytes: { kind: { type: numeric, precision: 12, scale: 0 }, required: true, description: "The size in bytes over which a version is listed (the daily check uses 80% of 6 MB: 5033164)" }
results:
  quizId:        { from: QbQuizVersion.quizId }
  versionId:     { from: QbQuizVersion.versionId }
  label:         { from: QbQuizVersion.label }
  title:         { from: QbQuizVersion.title }
  questionCount: { from: QbQuizVersion.questionCount }
  bytes:         { kind: { type: numeric, precision: 12, scale: 0 } }
list:
  sorts: [bytes]
  defaultSort: { field: bytes, asc: false }
  key: [versionId]
permissions: [qb.admin.quiz.read]
---*/
-- As qb.admin.content-health's SNAPSHOT_NEAR_LIMIT, one row a version (octet_length reads the stored size).
SELECT
    v.{{QbQuizVersion.quizId}}        AS quizId,
    v.{{QbQuizVersion.versionId}}     AS versionId,
    v.{{QbQuizVersion.label}}         AS label,
    v.{{QbQuizVersion.title}}         AS title,
    v.{{QbQuizVersion.questionCount}} AS questionCount,
    CAST(octet_length(v.{{QbQuizVersion.content}}) AS numeric) AS bytes
FROM {{QbQuizVersion}} v
WHERE octet_length(v.{{QbQuizVersion.content}}) > :overBytes
