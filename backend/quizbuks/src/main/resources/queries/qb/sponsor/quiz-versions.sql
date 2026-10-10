/*---
id: qb.sponsor.quiz-versions
description: The versions of one of the sponsor's quizzes (M-27), newest first; the content is read by version
entities: [QbQuizVersion, QbQuiz]
datasets:
  QbQuizVersion: urn:jabiz:dataset:sponsor:QbQuizVersion
  QbQuiz: urn:jabiz:dataset:sponsor:QbQuiz
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
permissions: [qb.content.write]
---*/
-- Through the sponsor's quizzes: the versions of a removed quiz are kept, but no longer listed to its sponsor.
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
JOIN {{QbQuiz}} z ON z.{{QbQuiz.quizId}} = v.{{QbQuizVersion.quizId}}
WHERE v.{{QbQuizVersion.quizId}} = :quizId
