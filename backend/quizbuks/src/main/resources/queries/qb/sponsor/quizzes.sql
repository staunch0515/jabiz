/*---
id: qb.sponsor.quizzes
description: The sponsor's own quizzes, not removed (M-20), and whether published; searched by title, ignoring case
entities: [QbQuiz, QbPublication]
datasets:
  QbQuiz: urn:jabiz:dataset:sponsor:QbQuiz
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
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
  publicationCount:    { kind: { type: numeric, precision: 9, scale: 0 } }
  published:           { kind: { type: bool } }
list:
  filters: [status, aiGenerated, editedAt, published]
  sorts:   [editedAt, title, questionCount]
  defaultSort: { field: editedAt, asc: false }
  key: [quizId]
permissions: [qb.content.write]
---*/
-- The text is found as written: strpos takes it literally, so % _ and \ match themselves. A quiz is published once a
-- publication of it was submitted (any state but a draft; Q4, M-20).
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
    z.{{QbQuiz.editedAt}}            AS editedAt,
    CAST(coalesce(n.submitted, 0) AS numeric(9, 0)) AS publicationCount,
    coalesce(n.submitted, 0) > 0     AS published
FROM {{QbQuiz}} z
LEFT JOIN (SELECT p.{{QbPublication.quizId}} AS of_quiz, count(*) AS submitted
           FROM {{QbPublication}} p
           WHERE p.{{QbPublication.status}} <> 'DRAFT'
           GROUP BY p.{{QbPublication.quizId}}) n ON n.of_quiz = z.{{QbQuiz.quizId}}
WHERE (CAST(:q AS text) IS NULL OR strpos(lower(z.{{QbQuiz.title}}), lower(CAST(:q AS text))) > 0)
