/*---
id: qb.sponsor.quizzes
description: The sponsor's own quizzes, not removed (M-20); searched by title, ignoring case
entities: [QbQuiz]
datasets:
  QbQuiz: urn:jabiz:dataset:sponsor:QbQuiz
params:
  q: { kind: { type: text, maxLength: 100 }, description: Only quizzes whose title contains this text }
results:
  quizId:              { from: QbQuiz.quizId }
  title:               { from: QbQuiz.title }
  cover:               { from: QbQuiz.cover }
  versionLabel:        { from: QbQuiz.versionLabel }
  latestVersionNo:     { from: QbQuiz.latestVersionNo }
  questionCount:       { from: QbQuiz.questionCount }
  materialCount:       { from: QbQuiz.materialCount }
  status:              { from: QbQuiz.status }
  changedSinceVersion: { from: QbQuiz.changedSinceVersion }
  aiGenerated:         { from: QbQuiz.aiGenerated }
  timeLimitSec:        { from: QbQuiz.timeLimitSec }
  editedAt:            { from: QbQuiz.editedAt }
list:
  filters: [status, aiGenerated, editedAt]
  sorts:   [editedAt, title, questionCount]
  defaultSort: { field: editedAt, asc: false }
  key: [quizId]
permissions: [qb.content.write]
---*/
-- The text is found as written: strpos takes it literally, so % _ and \ match themselves.
SELECT
    z.{{QbQuiz.quizId}}              AS quizId,
    z.{{QbQuiz.title}}               AS title,
    z.{{QbQuiz.cover}}               AS cover,
    z.{{QbQuiz.versionLabel}}        AS versionLabel,
    z.{{QbQuiz.latestVersionNo}}     AS latestVersionNo,
    z.{{QbQuiz.questionCount}}       AS questionCount,
    z.{{QbQuiz.materialCount}}       AS materialCount,
    z.{{QbQuiz.status}}              AS status,
    z.{{QbQuiz.changedSinceVersion}} AS changedSinceVersion,
    z.{{QbQuiz.aiGenerated}}         AS aiGenerated,
    z.{{QbQuiz.timeLimitSec}}        AS timeLimitSec,
    z.{{QbQuiz.editedAt}}            AS editedAt
FROM {{QbQuiz}} z
WHERE (CAST(:q AS text) IS NULL OR strpos(lower(z.{{QbQuiz.title}}), lower(CAST(:q AS text))) > 0)
