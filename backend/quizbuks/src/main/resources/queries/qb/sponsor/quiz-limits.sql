/*---
id: qb.sponsor.quiz-limits
description: >-
  F-S6: the question bank's limits as in effect now (docs/quizbuks/plans/impl/F-S6.md §3.5) - the most questions a
  quiz has, the most one attempt gives (a larger bank is drawn from) and the most one import batch adds; one row
entities: [SysParam]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  maxQuestions:  { kind: { type: numeric, precision: 6, scale: 0 } }
  maxPerAttempt: { kind: { type: numeric, precision: 6, scale: 0 } }
  importBatch:   { kind: { type: numeric, precision: 6, scale: 0 } }
permissions: [qb.content.write]
---*/
-- As content.QuizBankLimits reads them (QuizBankLimitsTemplateIT holds the two equal): a parameter not set up yet
-- counts as its declared value (QbParams: 1000, 100, 200), a fraction is cut, never below 1 nor above the technical
-- ceilings (ContentLimits.MAX_QUESTIONS_TECH 2000 for the bank and a batch, PlayLimits.MAX_PER_ATTEMPT_TECH 200).
WITH p AS (
    SELECT max(CASE WHEN s.{{SysParam.paramKey}} = 'qb.content.max-questions' THEN s.{{SysParam.value}} END) AS bank,
           max(CASE WHEN s.{{SysParam.paramKey}} = 'qb.play.max-questions-per-attempt'
                    THEN s.{{SysParam.value}} END) AS attempt,
           max(CASE WHEN s.{{SysParam.paramKey}} = 'qb.content.import.batch-size' THEN s.{{SysParam.value}} END)
               AS batch
    FROM {{SysParam}} s
    WHERE s.{{SysParam.paramKey}} IN ('qb.content.max-questions', 'qb.play.max-questions-per-attempt',
                                      'qb.content.import.batch-size'))
SELECT
    CAST(least(2000, greatest(1, trunc(coalesce(CASE WHEN p.bank ~ '^-?[0-9]+(\.[0-9]+)?$'
         THEN CAST(p.bank AS numeric) END, 1000)))) AS numeric(6, 0)) AS maxQuestions,
    CAST(least(200, greatest(1, trunc(coalesce(CASE WHEN p.attempt ~ '^-?[0-9]+(\.[0-9]+)?$'
         THEN CAST(p.attempt AS numeric) END, 100)))) AS numeric(6, 0)) AS maxPerAttempt,
    CAST(least(2000, greatest(1, trunc(coalesce(CASE WHEN p.batch ~ '^-?[0-9]+(\.[0-9]+)?$'
         THEN CAST(p.batch AS numeric) END, 200)))) AS numeric(6, 0)) AS importBatch
FROM p
