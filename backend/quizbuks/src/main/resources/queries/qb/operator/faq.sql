/*---
id: qb.operator.faq
description: >-
  The help centre's published questions and answers for the operators (audience OPERATOR; not those of the app or the
  sponsors), in their order; searched by text in any language. For the signed-in members of an operator only.
entities: [QbFaqEntry]
datasets:
  QbFaqEntry: urn:jabiz:dataset:default:QbFaqEntry
params:
  category: { like: QbFaqEntry.category, description: Only this category }
  q:        { kind: { type: text, maxLength: 100 }, description: Only entries whose question or answer contains this text }
results:
  faqId:    { from: QbFaqEntry.faqId }
  category: { from: QbFaqEntry.category }
  question: { from: QbFaqEntry.question }
  answer:   { from: QbFaqEntry.answer }
  seq:      { from: QbFaqEntry.seq }
list:
  filters: [category]
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [faqId]
permissions: [qb.operator.use]
---*/
-- The default dataset holds the drafts too: only the published entries of the operators' audience are read, whatever
-- the parameters or filters. Text is found as written in any language, literally (strpos), as in qb.public.faq.
SELECT
    f.{{QbFaqEntry.faqId}}    AS faqId,
    f.{{QbFaqEntry.category}} AS category,
    f.{{QbFaqEntry.question}} AS question,
    f.{{QbFaqEntry.answer}}   AS answer,
    f.{{QbFaqEntry.seq}}      AS seq
FROM {{QbFaqEntry}} f
WHERE f.{{QbFaqEntry.audience}} = 'OPERATOR'
  AND f.{{QbFaqEntry.published}}
  AND (CAST(:category AS text) IS NULL OR f.{{QbFaqEntry.category}} = CAST(:category AS text))
  AND (CAST(:q AS text) IS NULL
       OR EXISTS (SELECT 1 FROM jsonb_each_text(f.{{QbFaqEntry.question}}) e
                  WHERE strpos(lower(e.value), lower(CAST(:q AS text))) > 0)
       OR EXISTS (SELECT 1 FROM jsonb_each_text(f.{{QbFaqEntry.answer}}) e
                  WHERE strpos(lower(e.value), lower(CAST(:q AS text))) > 0))
