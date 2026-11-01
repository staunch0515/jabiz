/*---
id: qb.sponsor.analytics.languages
description: >-
  One of the sponsor's publications by the language its attempts are answered in (Q73e-1): starts, completions, and
  the passes, rates and average score only over the minimum group of completions in a language
entities: [QbPublicationCard, QbAttempt, QbStatsRun, SysParam]
datasets:
  QbPublicationCard: urn:jabiz:dataset:sponsor:QbPublicationCard
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbStatsRun: urn:jabiz:dataset:default:QbStatsRun
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  publicationId: { like: QbPublicationCard.publicationId, required: true, description: The publication }
results:
  language:       { kind: { type: text, maxLength: 2 } }
  seq:            { kind: { type: numeric, precision: 4, scale: 0 } }
  starts:         { kind: { type: numeric, precision: 12, scale: 0 } }
  completions:    { kind: { type: numeric, precision: 12, scale: 0 } }
  passes:         { kind: { type: numeric, precision: 12, scale: 0 } }
  completionRate: { kind: { type: numeric, precision: 5, scale: 4 } }
  passRate:       { kind: { type: numeric, precision: 5, scale: 4 } }
  averageScore:   { kind: { type: numeric, precision: 9, scale: 2 } }
  suppressed:     { kind: { type: bool } }
  minGroup:       { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [seq]
  defaultSort: { field: seq, asc: true }
  key: [language]
permissions: [qb.stats.read]
---*/
-- Per answer language (the attempt's, recorded at its start since Q73b; an attempt of before: the card's main language),
-- up to the analytics job's last run only, as the overview counts (review M1: the live counters would time one
-- taker's attempt). Every language the publication offers is listed, also one with no attempt yet. Starts and
-- completions are counts; the passes, the rates and the average score only over at least k completions in the language
-- (k as every analytics template: qb.stats.min-group if a whole number, else 5, never below 3), so that a language
-- few answer in does not show one taker's result. A completion is an attempt finished (submitted, or its time up).
WITH params AS (
    SELECT greatest(3, coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,9}$'
                                         THEN CAST(p.{{SysParam.value}} AS int) END), 5)) AS k
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.stats.min-group'),
run AS (
    SELECT max(r.{{QbStatsRun.refreshedAt}}) AS refreshed
    FROM {{QbStatsRun}} r
    WHERE r.{{QbStatsRun.job}} = 'qb.analytics'),
card AS (
    SELECT c.{{QbPublicationCard.publicationId}} AS pid, c.{{QbPublicationCard.language}} AS main,
           string_to_array(coalesce(c.{{QbPublicationCard.languages}}, c.{{QbPublicationCard.language}}), ',') AS offered
    FROM {{QbPublicationCard}} c
    WHERE c.{{QbPublicationCard.publicationId}} = :publicationId),
counted AS (
    SELECT coalesce(a.{{QbAttempt.language}}, card.main) AS lang,
           count(*) AS starts,
           count(*) FILTER (WHERE a.{{QbAttempt.status}} <> 'IN_PROGRESS'
               AND a.{{QbAttempt.submittedAt}} < run.refreshed) AS completions,
           count(*) FILTER (WHERE a.{{QbAttempt.status}} <> 'IN_PROGRESS' AND a.{{QbAttempt.passed}}
               AND a.{{QbAttempt.submittedAt}} < run.refreshed) AS passes,
           avg(a.{{QbAttempt.score}}) FILTER (WHERE a.{{QbAttempt.status}} <> 'IN_PROGRESS'
               AND a.{{QbAttempt.submittedAt}} < run.refreshed) AS score
    FROM card
    CROSS JOIN run
    JOIN {{QbAttempt}} a ON a.{{QbAttempt.publicationId}} = card.pid
    WHERE a.{{QbAttempt.startedAt}} < run.refreshed
    GROUP BY 1),
languages AS (
    SELECT l.lang, l.i FROM card CROSS JOIN unnest(card.offered) WITH ORDINALITY AS l(lang, i)
    UNION ALL
    SELECT c.lang, 100 FROM counted c, card WHERE c.lang <> ALL(card.offered))
SELECT
    CAST(l.lang AS varchar(2))                                    AS language,
    CAST(l.i AS numeric(4, 0))                                    AS seq,
    CAST(coalesce(c.starts, 0) AS numeric(12, 0))                 AS starts,
    CAST(coalesce(c.completions, 0) AS numeric(12, 0))            AS completions,
    CAST(CASE WHEN c.completions >= params.k THEN c.passes END AS numeric(12, 0)) AS passes,
    CAST(CASE WHEN c.completions >= params.k AND c.starts > 0
        THEN round(c.completions::numeric / c.starts, 4) END AS numeric(5, 4)) AS completionRate,
    CAST(CASE WHEN c.completions >= params.k
        THEN round(c.passes::numeric / c.completions, 4) END AS numeric(5, 4)) AS passRate,
    CAST(CASE WHEN c.completions >= params.k THEN round(c.score, 2) END AS numeric(9, 2)) AS averageScore,
    (coalesce(c.completions, 0) < params.k)                       AS suppressed,
    CAST(params.k AS numeric(9, 0))                               AS minGroup
FROM languages l
CROSS JOIN params
LEFT JOIN counted c ON c.lang = l.lang
