/*---
id: qb.public.faq
description: >-
  The help centre's published questions and answers for the app or the sponsor console (U-72, P-05; Q7b D-Q7-6), in
  their order; searched by text in any language
access: public
cacheSeconds: 300
timeoutMs: 2000
entities: [QbFaqEntry]
datasets:
  QbFaqEntry: urn:jabiz:dataset:public:QbFaqEntry
params:
  audience: { like: QbFaqEntry.audience, required: true, description: "APP or SPONSOR (entries for ALL are in both)" }
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
---*/
-- The public dataset holds the published entries only (its fixed scope); unpublished ones never appear, whatever the
-- parameters or filters. The text is found as written in any language: strpos takes it literally, so % _ and \ match
-- themselves.
SELECT
    f.{{QbFaqEntry.faqId}}    AS faqId,
    f.{{QbFaqEntry.category}} AS category,
    f.{{QbFaqEntry.question}} AS question,
    f.{{QbFaqEntry.answer}}   AS answer,
    f.{{QbFaqEntry.seq}}      AS seq
FROM {{QbFaqEntry}} f
WHERE f.{{QbFaqEntry.audience}} IN (CAST(:audience AS text), 'ALL')
  AND (CAST(:category AS text) IS NULL OR f.{{QbFaqEntry.category}} = CAST(:category AS text))
  AND (CAST(:q AS text) IS NULL
       OR EXISTS (SELECT 1 FROM jsonb_each_text(f.{{QbFaqEntry.question}}) e
                  WHERE strpos(lower(e.value), lower(CAST(:q AS text))) > 0)
       OR EXISTS (SELECT 1 FROM jsonb_each_text(f.{{QbFaqEntry.answer}}) e
                  WHERE strpos(lower(e.value), lower(CAST(:q AS text))) > 0))
