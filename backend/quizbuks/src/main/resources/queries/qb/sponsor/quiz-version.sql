/*---
id: qb.sponsor.quiz-version
description: One version of one of the sponsor's quizzes with its whole content (M-27); none once the quiz is removed
entities: [QbQuizVersion, QbQuiz]
datasets:
  QbQuizVersion: urn:jabiz:dataset:default:QbQuizVersion
  QbQuiz: urn:jabiz:dataset:sponsor:QbQuiz
params:
  versionId: { like: QbQuizVersion.versionId, required: true, description: The version }
results:
  versionId:     { from: QbQuizVersion.versionId }
  quizId:        { from: QbQuizVersion.quizId }
  versionNo:     { from: QbQuizVersion.versionNumber }
  label:         { from: QbQuizVersion.label }
  title:         { from: QbQuizVersion.title }
  timeLimitSec:  { from: QbQuizVersion.timeLimitSec }
  questionCount: { from: QbQuizVersion.questionCount }
  materialCount: { from: QbQuizVersion.materialCount }
  fullScore:     { from: QbQuizVersion.fullScore }
  content:       { from: QbQuizVersion.content }
  contentHash:   { from: QbQuizVersion.contentHash }
  versionedAt:   { from: QbQuizVersion.versionedAt }
list:
  key: [versionId]
permissions: [qb.content.write]
---*/
-- Only through the sponsor's own quizzes that are not removed, as qb.sponsor.quiz-versions.
SELECT
    v.{{QbQuizVersion.versionId}}     AS versionId,
    v.{{QbQuizVersion.quizId}}        AS quizId,
    v.{{QbQuizVersion.versionNumber}} AS versionNo,
    v.{{QbQuizVersion.label}}         AS label,
    v.{{QbQuizVersion.title}}         AS title,
    v.{{QbQuizVersion.timeLimitSec}}  AS timeLimitSec,
    v.{{QbQuizVersion.questionCount}} AS questionCount,
    v.{{QbQuizVersion.materialCount}} AS materialCount,
    v.{{QbQuizVersion.fullScore}}     AS fullScore,
    v.{{QbQuizVersion.content}}       AS content,
    v.{{QbQuizVersion.contentHash}}   AS contentHash,
    v.{{QbQuizVersion.versionedAt}}   AS versionedAt
FROM {{QbQuizVersion}} v
JOIN {{QbQuiz}} z ON z.{{QbQuiz.quizId}} = v.{{QbQuizVersion.quizId}}
WHERE v.{{QbQuizVersion.versionId}} = :versionId
