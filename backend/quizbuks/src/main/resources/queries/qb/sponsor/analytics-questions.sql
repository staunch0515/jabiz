/*---
id: qb.sponsor.analytics.questions
description: >-
  Each question of one of the sponsor's publications (M-42) - its text and points, how many answered it, the share
  answered right and its difficulty, only over the minimum group of completions
entities: [QbPublicationCard, QbQuizVersion, QbAnalyticsCursor, QbQuestionStat, SysParam]
datasets:
  QbPublicationCard: urn:jabiz:dataset:sponsor:QbPublicationCard
  QbQuizVersion: urn:jabiz:dataset:default:QbQuizVersion
  QbAnalyticsCursor: urn:jabiz:dataset:default:QbAnalyticsCursor
  QbQuestionStat: urn:jabiz:dataset:default:QbQuestionStat
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  publicationId: { like: QbPublicationCard.publicationId, required: true, description: The publication }
results:
  questionNo:  { kind: { type: numeric, precision: 4, scale: 0 } }
  stem:        { kind: { type: text, maxLength: 1000 } }
  points:      { kind: { type: numeric, precision: 4, scale: 0 } }
  completions: { kind: { type: numeric, precision: 12, scale: 0 } }
  correct:     { kind: { type: numeric, precision: 12, scale: 0 } }
  correctRate: { kind: { type: numeric, precision: 5, scale: 4 } }
  difficulty:  { kind: { type: text, maxLength: 10 } }
  suppressed:  { kind: { type: bool } }
  basedOn:     { kind: { type: numeric, precision: 12, scale: 0 } }
  minGroup:    { kind: { type: numeric, precision: 9, scale: 0 } }
  shownAt:     { from: QbQuestionStat.shownAt }
list:
  sorts: [questionNo, correctRate]
  defaultSort: { field: questionNo, asc: true }
  key: [questionNo]
permissions: [qb.stats.read]
---*/
-- Only a publication of the sponsor's own; the questions (number, text, points; never the options or the right
-- answers) from the version it froze. The figures are the job's shown ones (stats.MinGroup, E-4): they change only
-- after at least k new completions, so two looks never tell one person's answers, and are given only over at least k
-- completions (suppressed otherwise): k is qb.stats.min-group if a whole number, else 5, never below 3, as in every
-- analytics template. Right = the question earned points (a multiple choice: all of it right, as scored). Difficulty
-- (E-5): EASY from 80 % right, MEDIUM from 50 %, HARD below.
WITH params AS (
    SELECT greatest(3, coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,9}$'
                                         THEN CAST(p.{{SysParam.value}} AS int) END), 5)) AS k
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.stats.min-group'),
pub AS (
    SELECT p.{{QbPublicationCard.publicationId}} AS pid, p.{{QbPublicationCard.versionId}} AS vid
    FROM {{QbPublicationCard}} p
    WHERE p.{{QbPublicationCard.publicationId}} = :publicationId),
qs AS (
    SELECT b.pid, CAST(q.value ->> 'no' AS int) AS no, q.value ->> 'stem' AS stem,
           CAST(q.value ->> 'points' AS int) AS points
    FROM pub b
    JOIN {{QbQuizVersion}} v ON v.{{QbQuizVersion.versionId}} = b.vid
    CROSS JOIN LATERAL jsonb_array_elements(CAST(v.{{QbQuizVersion.content}} AS jsonb) -> 'questions') q),
figures AS (
    SELECT q.no, q.stem, q.points, s.{{QbQuestionStat.completions}} AS v_completions,
           s.{{QbQuestionStat.correct}} AS v_correct, s.{{QbQuestionStat.shownAt}} AS v_shown_at,
           coalesce(c.{{QbAnalyticsCursor.shownAnalysed}}, 0) AS v_based_on, m.k,
           s.{{QbQuestionStat.completions}} IS NOT NULL
               AND coalesce(c.{{QbAnalyticsCursor.shownAnalysed}}, 0) >= m.k
               AND s.{{QbQuestionStat.completions}} >= m.k AS shown
    FROM qs q
    CROSS JOIN params m
    LEFT JOIN {{QbAnalyticsCursor}} c ON c.{{QbAnalyticsCursor.publicationId}} = q.pid
    LEFT JOIN {{QbQuestionStat}} s ON s.{{QbQuestionStat.publicationId}} = q.pid
        AND s.{{QbQuestionStat.questionNo}} = q.no)
SELECT
    CAST(f.no AS numeric(4, 0))                                   AS questionNo,
    CAST(f.stem AS varchar(1000))                                 AS stem,
    CAST(f.points AS numeric(4, 0))                               AS points,
    CASE WHEN f.shown THEN CAST(f.v_completions AS numeric(12, 0)) END AS completions,
    CASE WHEN f.shown THEN CAST(f.v_correct AS numeric(12, 0)) END AS correct,
    CASE WHEN f.shown THEN round(CAST(f.v_correct AS numeric) / f.v_completions, 4) END AS correctRate,
    CAST(CASE WHEN NOT f.shown THEN NULL
              WHEN CAST(f.v_correct AS numeric) / f.v_completions >= 0.8 THEN 'EASY'
              WHEN CAST(f.v_correct AS numeric) / f.v_completions >= 0.5 THEN 'MEDIUM'
              ELSE 'HARD' END AS varchar(10))                        AS difficulty,
    NOT f.shown                                                   AS suppressed,
    CAST(f.v_based_on AS numeric(12, 0))                          AS basedOn,
    CAST(f.k AS numeric(9, 0))                                    AS minGroup,
    CASE WHEN f.shown THEN f.v_shown_at END                       AS shownAt
FROM figures f
