/*---
id: qb.admin.quiz-versions
description: The versions of any quiz, newest first, without their content (read by version, which holds the answers)
entities: [QbQuizVersion]
datasets:
  QbQuizVersion: urn:jabiz:dataset:default:QbQuizVersion
params:
  quizId: { like: QbQuizVersion.quizId, required: true, description: The quiz }
results:
  versionId:     { from: QbQuizVersion.versionId }
  versionNo:     { from: QbQuizVersion.versionNumber }
  label:         { from: QbQuizVersion.label }
  title:         { from: QbQuizVersion.title }
  questionCount: { from: QbQuizVersion.questionCount }
  fullScore:     { from: QbQuizVersion.fullScore }
  versionedAt:   { from: QbQuizVersion.versionedAt }
  contentHash:   { from: QbQuizVersion.contentHash }
list:
  filters: [versionNo, versionedAt]
  sorts:   [versionNo, versionedAt]
  defaultSort: { field: versionNo, asc: false }
  key: [versionId]
permissions: [qb.admin.quiz.read]
---*/
-- As qb.sponsor.quiz-versions for any quiz (Q17); a removed quiz's versions stay, as publications refer to them.
SELECT
    v.{{QbQuizVersion.versionId}}     AS versionId,
    v.{{QbQuizVersion.versionNumber}} AS versionNo,
    v.{{QbQuizVersion.label}}         AS label,
    v.{{QbQuizVersion.title}}         AS title,
    v.{{QbQuizVersion.questionCount}} AS questionCount,
    v.{{QbQuizVersion.fullScore}}     AS fullScore,
    v.{{QbQuizVersion.versionedAt}}   AS versionedAt,
    v.{{QbQuizVersion.contentHash}}   AS contentHash
FROM {{QbQuizVersion}} v
WHERE v.{{QbQuizVersion.quizId}} = :quizId
