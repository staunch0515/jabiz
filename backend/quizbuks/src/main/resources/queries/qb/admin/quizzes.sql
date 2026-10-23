/*---
id: qb.admin.quizzes
description: Every sponsor's quizzes, removed ones included, with how many publications each has; searched by title
entities: [QbQuiz, QbPublication]
datasets:
  QbQuiz: urn:jabiz:dataset:default:QbQuiz
  QbPublication: urn:jabiz:dataset:default:QbPublication
params:
  q: { kind: { type: text, maxLength: 100 }, description: Only quizzes whose title contains this text }
results:
  quizId:              { from: QbQuiz.quizId }
  ownerId:             { from: QbQuiz.ownerId }
  title:               { from: QbQuiz.title }
  kind:                { from: QbQuiz.kind }
  cover:               { from: QbQuiz.cover }
  status:              { from: QbQuiz.status }
  removed:             { from: QbQuiz.removed }
  aiGenerated:         { from: QbQuiz.aiGenerated }
  versionLabel:        { from: QbQuiz.versionLabel }
  latestVersionNo:     { from: QbQuiz.latestVersionNo }
  changedSinceVersion: { from: QbQuiz.changedSinceVersion }
  questionCount:       { from: QbQuiz.questionCount }
  materialCount:       { from: QbQuiz.materialCount }
  timeLimitSec:        { from: QbQuiz.timeLimitSec }
  clonedFrom:          { from: QbQuiz.clonedFrom }
  editedAt:            { from: QbQuiz.editedAt }
  publicationCount:    { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  filters: [quizId, ownerId, kind, status, removed, editedAt]
  sorts:   [editedAt, title, questionCount]
  defaultSort: { field: editedAt, asc: false }
  key: [quizId]
permissions: [qb.admin.quiz.read]
---*/
-- As qb.sponsor.quizzes, for every sponsor (Q17): the content administrators see what sponsors made, not only what
-- was submitted for review. Every publication counts, drafts included; the text is found as written (strpos).
SELECT
    z.{{QbQuiz.quizId}}              AS quizId,
    z.{{QbQuiz.ownerId}}             AS ownerId,
    z.{{QbQuiz.title}}               AS title,
    z.{{QbQuiz.kind}}                AS kind,
    z.{{QbQuiz.cover}}               AS cover,
    z.{{QbQuiz.status}}              AS status,
    z.{{QbQuiz.removed}}             AS removed,
    z.{{QbQuiz.aiGenerated}}         AS aiGenerated,
    z.{{QbQuiz.versionLabel}}        AS versionLabel,
    z.{{QbQuiz.latestVersionNo}}     AS latestVersionNo,
    z.{{QbQuiz.changedSinceVersion}} AS changedSinceVersion,
    z.{{QbQuiz.questionCount}}       AS questionCount,
    z.{{QbQuiz.materialCount}}       AS materialCount,
    z.{{QbQuiz.timeLimitSec}}        AS timeLimitSec,
    z.{{QbQuiz.clonedFrom}}          AS clonedFrom,
    z.{{QbQuiz.editedAt}}            AS editedAt,
    CAST(coalesce(n.publications, 0) AS numeric(9, 0)) AS publicationCount
FROM {{QbQuiz}} z
LEFT JOIN (SELECT p.{{QbPublication.quizId}} AS of_quiz, count(*) AS publications
           FROM {{QbPublication}} p
           GROUP BY p.{{QbPublication.quizId}}) n ON n.of_quiz = z.{{QbQuiz.quizId}}
WHERE (CAST(:q AS text) IS NULL OR strpos(lower(z.{{QbQuiz.title}}), lower(CAST(:q AS text))) > 0)
