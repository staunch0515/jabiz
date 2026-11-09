/*---
id: qb.sponsor.quiz-translation-progress
description: >-
  F-S6c: how far each of the other languages of one of the sponsor's own quizzes is translated
  (docs/quizbuks/plans/impl/F-S6.md §3.5b) - per language, how many of the base's texts (title, introduction, each
  question's text and each option's) have no translation and how many are out of date, by the rule of
  content.QuizLanguages and qb.sponsor.quiz-translations; the materials are counted by the page from the draft
entities: [QbQuiz, QbQuestion, QbOption, QbContentTranslation]
datasets:
  QbQuiz: urn:jabiz:dataset:sponsor:QbQuiz
  QbQuestion: urn:jabiz:dataset:sponsor:QbQuestion
  QbOption: urn:jabiz:dataset:sponsor:QbOption
  QbContentTranslation: urn:jabiz:dataset:sponsor:QbContentTranslation
params:
  quizId: { like: QbQuiz.quizId, required: true, description: The quiz }
results:
  language: { kind: { type: text, maxLength: 2 } }
  missing:  { kind: { type: numeric, precision: 6, scale: 0 } }
  stale:    { kind: { type: numeric, precision: 6, scale: 0 } }
list:
  sorts: [language]
  defaultSort: { field: language, asc: true }
  key: [language]
permissions: [qb.content.write]
---*/
-- Only a quiz of the caller's own (its datasets hold only theirs); one row per language of its translationLanguages.
-- A text's state is that of qb.sponsor.quiz-translations: none when the base text is blank, missing without a
-- translation, stale when the translation's sourceHash is not the hash of the present base text
-- (ContentLanguages.sourceHash: the first 16 hexadecimal characters of the SHA-256 of the NFC form in UTF-8).
SELECT
    g.language AS language,
    CAST(count(*) FILTER (WHERE g.state = 'missing') AS numeric(6, 0)) AS missing,
    CAST(count(*) FILTER (WHERE g.state = 'stale') AS numeric(6, 0))   AS stale
FROM (SELECT
          l.language,
          CASE
              WHEN coalesce(e.source ~ '[^[:space:]]', false) IS NOT TRUE THEN 'none'
              WHEN coalesce(t.{{QbContentTranslation.text}} ~ '[^[:space:]]', false) IS NOT TRUE THEN 'missing'
              WHEN t.{{QbContentTranslation.sourceHash}} IS DISTINCT FROM
                   substr(encode(sha256(convert_to(normalize(e.source, NFC), 'UTF8')), 'hex'), 1, 16) THEN 'stale'
              ELSE 'done' END AS state
      FROM (SELECT
                z.{{QbQuiz.quizId}} AS quizId,
                z.{{QbQuiz.title}}  AS title,
                z.{{QbQuiz.intro}}  AS intro,
                CAST(btrim(lang) AS varchar(2)) AS language
            FROM {{QbQuiz}} z
            CROSS JOIN LATERAL unnest(string_to_array(z.{{QbQuiz.translationLanguages}}, ',')) AS lang
            WHERE z.{{QbQuiz.quizId}} = :quizId
              AND btrim(lang) <> '') l
      CROSS JOIN LATERAL (
          SELECT 'quiz.title' AS elementKey, l.title AS source
          UNION ALL
          SELECT 'quiz.intro', l.intro
          UNION ALL
          SELECT 'question:' || CAST(q.{{QbQuestion.questionId}} AS text) || '.stem', q.{{QbQuestion.stem}}
          FROM {{QbQuestion}} q
          WHERE q.{{QbQuestion.quizId}} = l.quizId
          UNION ALL
          SELECT 'option:' || CAST(o.{{QbOption.optionId}} AS text) || '.text', o.{{QbOption.text}}
          FROM {{QbOption}} o
          WHERE o.{{QbOption.quizId}} = l.quizId) e
      LEFT JOIN {{QbContentTranslation}} t
             ON t.{{QbContentTranslation.quizId}} = l.quizId
            AND t.{{QbContentTranslation.language}} = l.language
            AND t.{{QbContentTranslation.elementKey}} = e.elementKey) g
GROUP BY g.language
