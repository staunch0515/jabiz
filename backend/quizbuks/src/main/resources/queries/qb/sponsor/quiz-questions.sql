/*---
id: qb.sponsor.quiz-questions
description: >-
  F-S6: the questions of one of the sponsor's own quizzes, a page at a time (docs/quizbuks/plans/impl/F-S6.md §3.1) -
  each with its number in the quiz, text, points, number of options and in how many of the quiz's translation
  languages its text is translated; F-S6d: how many of its options are correct and whether any has an image (the
  list's badges, without the draft's options); searched by text (ignoring case) or by number
entities: [QbQuiz, QbQuestion, QbOption, QbContentTranslation]
datasets:
  QbQuiz: urn:jabiz:dataset:sponsor:QbQuiz
  QbQuestion: urn:jabiz:dataset:sponsor:QbQuestion
  QbOption: urn:jabiz:dataset:sponsor:QbOption
  QbContentTranslation: urn:jabiz:dataset:sponsor:QbContentTranslation
params:
  quizId: { like: QbQuiz.quizId, required: true, description: The quiz }
  q:      { kind: { type: text, maxLength: 100 }, description: Only questions whose text contains this text }
  no:     { kind: { type: numeric, precision: 4, scale: 0 }, description: Only the question with this number }
results:
  questionId:  { from: QbQuestion.questionId }
  questionNo:  { kind: { type: numeric, precision: 4, scale: 0 } }
  stem:        { from: QbQuestion.stem }
  image:       { from: QbQuestion.image }
  points:      { from: QbQuestion.points }
  selection:   { from: QbQuestion.selection }
  optionCount: { kind: { type: numeric, precision: 4, scale: 0 } }
  translated:  { kind: { type: numeric, precision: 4, scale: 0 } }
  correctCount: { kind: { type: numeric, precision: 4, scale: 0 } }
  optionImages: { kind: { type: bool } }
list:
  sorts: [questionNo]
  defaultSort: { field: questionNo, asc: true }
  key: [questionId]
permissions: [qb.content.write]
---*/
-- Only a quiz of the caller's own (its datasets hold only theirs). The number is the place in the quiz's order (by
-- place, then key, as the editor and a version number them), counted before the search, so a question found keeps
-- its number. The text is found as written: strpos takes it literally. "translated" counts the languages with a
-- translation of the question's text (its options are counted by the translation page).
SELECT
    n.questionId  AS questionId,
    n.questionNo  AS questionNo,
    n.stem        AS stem,
    n.image       AS image,
    n.points      AS points,
    n.selection   AS selection,
    CAST((SELECT count(*) FROM {{QbOption}} o
          WHERE o.{{QbOption.questionId}} = n.questionId) AS numeric(4, 0)) AS optionCount,
    CAST((SELECT count(*) FROM {{QbOption}} o
          WHERE o.{{QbOption.questionId}} = n.questionId AND o.{{QbOption.correct}}) AS numeric(4, 0)) AS correctCount,
    EXISTS (SELECT 1 FROM {{QbOption}} o
            WHERE o.{{QbOption.questionId}} = n.questionId AND o.{{QbOption.image}} IS NOT NULL) AS optionImages,
    CAST((SELECT count(DISTINCT t.{{QbContentTranslation.language}}) FROM {{QbContentTranslation}} t
          WHERE t.{{QbContentTranslation.quizId}} = n.quizId
            AND t.{{QbContentTranslation.elementKey}} = 'question:' || CAST(n.questionId AS text) || '.stem'
            AND t.{{QbContentTranslation.text}} IS NOT NULL) AS numeric(4, 0)) AS translated
FROM (SELECT
          q.{{QbQuestion.questionId}} AS questionId,
          q.{{QbQuestion.quizId}}     AS quizId,
          q.{{QbQuestion.stem}}       AS stem,
          q.{{QbQuestion.image}}      AS image,
          q.{{QbQuestion.points}}     AS points,
          q.{{QbQuestion.selection}}  AS selection,
          CAST(row_number() OVER (ORDER BY q.{{QbQuestion.seq}}, CAST(q.{{QbQuestion.questionId}} AS text))
               AS numeric(4, 0))      AS questionNo
      FROM {{QbQuestion}} q
      JOIN {{QbQuiz}} z ON z.{{QbQuiz.quizId}} = q.{{QbQuestion.quizId}}
      WHERE q.{{QbQuestion.quizId}} = :quizId) n
WHERE (CAST(:q AS text) IS NULL OR strpos(lower(coalesce(n.stem, '')), lower(CAST(:q AS text))) > 0)
  AND (CAST(:no AS numeric) IS NULL OR n.questionNo = :no)
