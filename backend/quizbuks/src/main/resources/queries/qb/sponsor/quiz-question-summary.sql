/*---
id: qb.sponsor.quiz-question-summary
description: >-
  F-S6d: the summary line over the questions of one of the sponsor's own quizzes (docs/quizbuks/plans/impl/F-S6.md
  §3.5c), counted by the server so the editor needs no question of its own - how many questions, the full score, how
  many are single and how many multiple choice (by their correct options, as QuizSnapshot.Question.choice), how many
  have no correct option, and the first of those (its key and number) to go to
entities: [QbQuiz, QbQuestion, QbOption]
datasets:
  QbQuiz: urn:jabiz:dataset:sponsor:QbQuiz
  QbQuestion: urn:jabiz:dataset:sponsor:QbQuestion
  QbOption: urn:jabiz:dataset:sponsor:QbOption
params:
  quizId: { like: QbQuiz.quizId, required: true, description: The quiz }
results:
  quizId:          { from: QbQuiz.quizId }
  questionCount:   { kind: { type: numeric, precision: 6, scale: 0 } }
  fullScore:       { kind: { type: numeric, precision: 9, scale: 0 } }
  singleCount:     { kind: { type: numeric, precision: 6, scale: 0 } }
  multipleCount:   { kind: { type: numeric, precision: 6, scale: 0 } }
  unmarkedCount:   { kind: { type: numeric, precision: 6, scale: 0 } }
  firstUnmarkedId: { from: QbQuestion.questionId }
  firstUnmarkedNo: { kind: { type: numeric, precision: 4, scale: 0 } }
permissions: [qb.content.write]
---*/
-- Only a quiz of the caller's own (its datasets hold only theirs): no row for another's. The numbers are the places in
-- the quiz's order (by place, then key, as quiz-questions numbers them). A question with no correct option counts as
-- unmarked whatever its kind; the page shows it for a scored quiz only.
SELECT
    z.{{QbQuiz.quizId}} AS quizId,
    CAST(count(n.questionId) AS numeric(6, 0))                              AS questionCount,
    CAST(coalesce(sum(n.points), 0) AS numeric(9, 0))                       AS fullScore,
    CAST(count(*) FILTER (WHERE n.correct = 1) AS numeric(6, 0))            AS singleCount,
    CAST(count(*) FILTER (WHERE n.correct > 1) AS numeric(6, 0))            AS multipleCount,
    CAST(count(*) FILTER (WHERE n.correct = 0) AS numeric(6, 0))            AS unmarkedCount,
    (array_agg(n.questionId ORDER BY n.questionNo) FILTER (WHERE n.correct = 0))[1] AS firstUnmarkedId,
    CAST(min(n.questionNo) FILTER (WHERE n.correct = 0) AS numeric(4, 0))   AS firstUnmarkedNo
FROM {{QbQuiz}} z
LEFT JOIN (SELECT
               q.{{QbQuestion.questionId}} AS questionId,
               q.{{QbQuestion.quizId}}     AS quizId,
               q.{{QbQuestion.points}}     AS points,
               (SELECT count(*) FROM {{QbOption}} o
                WHERE o.{{QbOption.questionId}} = q.{{QbQuestion.questionId}} AND o.{{QbOption.correct}}) AS correct,
               row_number() OVER (ORDER BY q.{{QbQuestion.seq}}, CAST(q.{{QbQuestion.questionId}} AS text)) AS questionNo
           FROM {{QbQuestion}} q
           WHERE q.{{QbQuestion.quizId}} = :quizId) n ON n.quizId = z.{{QbQuiz.quizId}}
WHERE z.{{QbQuiz.quizId}} = :quizId
GROUP BY z.{{QbQuiz.quizId}}
