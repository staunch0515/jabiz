/*---
id: qb.sponsor.quiz-translations
description: >-
  F-S6c: the questions of one of the sponsor's own quizzes in one of its other languages, a page at a time
  (docs/quizbuks/plans/impl/F-S6.md §3.1, §3.5a) - each with its number, its base text and image, the translation
  of its text and its own image in the language, and its options (a JSON list) the same way; each text's state is
  none, missing, stale or done, by the rule of content.QuizLanguages; searched by the base text (ignoring case), or
  only the questions with a text missing or out of date
entities: [QbQuiz, QbQuestion, QbOption, QbContentTranslation]
datasets:
  QbQuiz: urn:jabiz:dataset:sponsor:QbQuiz
  QbQuestion: urn:jabiz:dataset:sponsor:QbQuestion
  QbOption: urn:jabiz:dataset:sponsor:QbOption
  QbContentTranslation: urn:jabiz:dataset:sponsor:QbContentTranslation
params:
  quizId:       { like: QbQuiz.quizId, required: true, description: The quiz }
  language:     { like: QbContentTranslation.language, required: true, description: The language translated into }
  q:            { kind: { type: text, maxLength: 100 }, description: Only questions whose text or an option's contains this text }
  untranslated: { kind: { type: bool }, description: "If true, only questions with a text missing or out of date" }
results:
  questionId:  { from: QbQuestion.questionId }
  questionNo:  { kind: { type: numeric, precision: 4, scale: 0 } }
  stem:        { from: QbQuestion.stem }
  image:       { from: QbQuestion.image }
  stemText:    { from: QbContentTranslation.text }
  stemState:   { kind: { type: text, maxLength: 8 } }
  stemBy:      { from: QbContentTranslation.translatedBy }
  ownImage:    { from: QbContentTranslation.image }
  options:     { kind: { type: text } }
  pending:     { kind: { type: numeric, precision: 4, scale: 0 } }
list:
  sorts: [questionNo]
  defaultSort: { field: questionNo, asc: true }
  key: [questionId]
permissions: [qb.content.write]
---*/
-- Only a quiz of the caller's own (its datasets hold only theirs). The number is the place in the quiz's order (by
-- place, then key, as the editor, quiz-questions and a version number them), counted before the search and the
-- filter, so a question found keeps its number.
--
-- A text's state is QuizLanguages's: none when the base has no text (blank); missing when the language has no text
-- for it; stale when the translation was made from another base text, that is when its sourceHash is not the hash
-- of the present one (ContentLanguages.sourceHash: the first 16 hexadecimal characters of the SHA-256 of the NFC
-- form in UTF-8); else done. "pending" counts the question's texts (its own and its options') missing or stale.
-- The search takes the text literally (strpos), in the question's text or any of its options'.
SELECT
    s.questionId AS questionId,
    s.questionNo AS questionNo,
    s.stem       AS stem,
    s.image      AS image,
    s.stemText   AS stemText,
    s.stemState  AS stemState,
    s.stemBy     AS stemBy,
    s.ownImage   AS ownImage,
    s.options    AS options,
    CAST(s.pending AS numeric(4, 0)) AS pending
FROM (SELECT
          n.questionId,
          n.questionNo,
          n.stem,
          n.image,
          st.{{QbContentTranslation.text}}         AS stemText,
          st.{{QbContentTranslation.translatedBy}} AS stemBy,
          qi.{{QbContentTranslation.image}}        AS ownImage,
          CAST(CASE
              WHEN coalesce(n.stem ~ '[^[:space:]]', false) IS NOT TRUE THEN 'none'
              WHEN coalesce(st.{{QbContentTranslation.text}} ~ '[^[:space:]]', false) IS NOT TRUE THEN 'missing'
              WHEN st.{{QbContentTranslation.sourceHash}} IS DISTINCT FROM
                   substr(encode(sha256(convert_to(normalize(n.stem, NFC), 'UTF8')), 'hex'), 1, 16) THEN 'stale'
              ELSE 'done' END AS varchar(8)) AS stemState,
          o.options,
          o.found,
          o.pending + CASE
              WHEN coalesce(n.stem ~ '[^[:space:]]', false) IS NOT TRUE THEN 0
              WHEN coalesce(st.{{QbContentTranslation.text}} ~ '[^[:space:]]', false) IS NOT TRUE THEN 1
              WHEN st.{{QbContentTranslation.sourceHash}} IS DISTINCT FROM
                   substr(encode(sha256(convert_to(normalize(n.stem, NFC), 'UTF8')), 'hex'), 1, 16) THEN 1
              ELSE 0 END AS pending
      FROM (SELECT
                q.{{QbQuestion.questionId}} AS questionId,
                q.{{QbQuestion.quizId}}     AS quizId,
                q.{{QbQuestion.stem}}       AS stem,
                q.{{QbQuestion.image}}      AS image,
                CAST(row_number() OVER (ORDER BY q.{{QbQuestion.seq}}, CAST(q.{{QbQuestion.questionId}} AS text))
                     AS numeric(4, 0))      AS questionNo
            FROM {{QbQuestion}} q
            JOIN {{QbQuiz}} z ON z.{{QbQuiz.quizId}} = q.{{QbQuestion.quizId}}
            WHERE q.{{QbQuestion.quizId}} = :quizId) n
      LEFT JOIN {{QbContentTranslation}} st
             ON st.{{QbContentTranslation.quizId}} = n.quizId
            AND st.{{QbContentTranslation.language}} = :language
            AND st.{{QbContentTranslation.elementKey}} = 'question:' || CAST(n.questionId AS text) || '.stem'
      LEFT JOIN {{QbContentTranslation}} qi
             ON qi.{{QbContentTranslation.quizId}} = n.quizId
            AND qi.{{QbContentTranslation.language}} = :language
            AND qi.{{QbContentTranslation.elementKey}} = 'question:' || CAST(n.questionId AS text) || '.image'
      CROSS JOIN LATERAL (
          SELECT
              CAST(coalesce(jsonb_agg(jsonb_build_object(
                  'optionId', CAST(p.optionId AS text), 'no', p.no, 'text', p.text, 'image', p.image,
                  'translation', p.translation, 'state', p.state, 'by', p.madeBy, 'ownImage', p.ownImage)
                  ORDER BY p.no), '[]'::jsonb) AS text) AS options,
              count(*) FILTER (WHERE p.state IN ('missing', 'stale')) AS pending,
              bool_or(CAST(:q AS text) IS NOT NULL
                      AND strpos(lower(coalesce(p.text, '')), lower(CAST(:q AS text))) > 0) AS found
          FROM (SELECT
                    op.{{QbOption.optionId}} AS optionId,
                    row_number() OVER (ORDER BY op.{{QbOption.seq}}, CAST(op.{{QbOption.optionId}} AS text)) AS no,
                    op.{{QbOption.text}}     AS text,
                    op.{{QbOption.image}}    AS image,
                    ot.{{QbContentTranslation.text}}         AS translation,
                    ot.{{QbContentTranslation.translatedBy}} AS madeBy,
                    oi.{{QbContentTranslation.image}}        AS ownImage,
                    CASE
                        WHEN coalesce(op.{{QbOption.text}} ~ '[^[:space:]]', false) IS NOT TRUE THEN 'none'
                        WHEN coalesce(ot.{{QbContentTranslation.text}} ~ '[^[:space:]]', false) IS NOT TRUE
                            THEN 'missing'
                        WHEN ot.{{QbContentTranslation.sourceHash}} IS DISTINCT FROM
                             substr(encode(sha256(convert_to(normalize(op.{{QbOption.text}}, NFC), 'UTF8')), 'hex'),
                                    1, 16) THEN 'stale'
                        ELSE 'done' END AS state
                FROM {{QbOption}} op
                LEFT JOIN {{QbContentTranslation}} ot
                       ON ot.{{QbContentTranslation.quizId}} = n.quizId
                      AND ot.{{QbContentTranslation.language}} = :language
                      AND ot.{{QbContentTranslation.elementKey}}
                          = 'option:' || CAST(op.{{QbOption.optionId}} AS text) || '.text'
                LEFT JOIN {{QbContentTranslation}} oi
                       ON oi.{{QbContentTranslation.quizId}} = n.quizId
                      AND oi.{{QbContentTranslation.language}} = :language
                      AND oi.{{QbContentTranslation.elementKey}}
                          = 'option:' || CAST(op.{{QbOption.optionId}} AS text) || '.image'
                WHERE op.{{QbOption.questionId}} = n.questionId) p) o) s
WHERE (CAST(:q AS text) IS NULL OR strpos(lower(coalesce(s.stem, '')), lower(CAST(:q AS text))) > 0
       OR s.found IS TRUE)
  AND (CAST(:untranslated AS boolean) IS NOT TRUE OR s.pending > 0)
