/*---
id: qb.system.analytics-attempts
description: >-
  The attempts some publications finished since the analytics job's watermark (or all of them, to recount a
  publication whole), summed per version and question (Q51b) and per publication
entities: [QbPublicationCard, QbAnalyticsCursor, QbAttempt]
datasets:
  QbPublicationCard: urn:jabiz:dataset:stats:QbPublicationCard
  QbAnalyticsCursor: urn:jabiz:dataset:default:QbAnalyticsCursor
  QbAttempt: urn:jabiz:dataset:stats:QbAttempt
params:
  ids:   { like: QbPublicationCard.publicationId, list: true, required: true, description: The publications }
  until: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: Attempts finished up to this time are read }
  full:  { kind: { type: bool }, required: true, description: "Every attempt (a recount), not those after the watermark" }
  cap:   { kind: { type: numeric, precision: 9, scale: 0 }, required: true, description: Most attempts read in all (not for a recount) }
results:
  publicationId: { from: QbPublicationCard.publicationId }
  versionId:     { from: QbAttempt.versionId }
  vAttempts:     { kind: { type: numeric, precision: 12, scale: 0 } }
  questionNo:    { kind: { type: numeric, precision: 4, scale: 0 } }
  qCompletions:  { kind: { type: numeric, precision: 12, scale: 0 } }
  qCorrect:      { kind: { type: numeric, precision: 12, scale: 0 } }
  attempts:      { kind: { type: numeric, precision: 12, scale: 0 } }
  submitted:     { kind: { type: numeric, precision: 12, scale: 0 } }
  expired:       { kind: { type: numeric, precision: 12, scale: 0 } }
  durationSec:   { kind: { type: numeric, precision: 15, scale: 3 } }
  lastAt:        { kind: { type: temporal, role: EVENT_TIME } }
  lastId:        { kind: { type: text, maxLength: 36 } }
list:
  key: [publicationId, versionId, questionNo]
permissions: [qb.stats.system]
---*/
-- The finished attempts in (submitted_at, attempt_id) order after each publication's watermark, through the index on
-- (publication, submission time) (no index is added to qb_attempt). "until" lags the run (stats.AnalyticsLimits.LAG):
-- a submission's time is taken before its transaction commits. Incrementally at most "cap" attempts in all, the
-- earliest first: each publication keeps a prefix of its own order, so its watermark moves to the last one read and the
-- rest is read by the next runs. Each result is the canonical JSON of play.Scoring (no, earned, ...): a question is
-- right when it earned points, which for a multiple choice means all of it right; a result that is not such JSON
-- (it never is) counts as a completion without questions instead of failing the chunk. Average times count the submitted
-- attempts only (an expired one took the whole time limit, E-6). Questions are counted per version the attempt ran on
-- (Q51b): after a switch the same numbers are other questions.
WITH pubs AS (
    SELECT k.{{QbPublicationCard.publicationId}} AS pid,
           c.{{QbAnalyticsCursor.watermarkAt}} AS wm_at, c.{{QbAnalyticsCursor.watermarkId}} AS wm_id
    FROM {{QbPublicationCard}} k
    LEFT JOIN {{QbAnalyticsCursor}} c ON c.{{QbAnalyticsCursor.publicationId}} = k.{{QbPublicationCard.publicationId}}
    WHERE k.{{QbPublicationCard.publicationId}} = ANY(:ids)),
candidates AS (
    SELECT p.pid, a.aid, a.sat
    FROM pubs p
    CROSS JOIN LATERAL (
        SELECT t.{{QbAttempt.attemptId}} AS aid, t.{{QbAttempt.submittedAt}} AS sat
        FROM {{QbAttempt}} t
        WHERE t.{{QbAttempt.publicationId}} = p.pid
          AND t.{{QbAttempt.submittedAt}} IS NOT NULL
          AND t.{{QbAttempt.submittedAt}} <= CAST(:until AS timestamptz)
          AND (CAST(:full AS boolean) OR p.wm_at IS NULL
               OR (t.{{QbAttempt.submittedAt}} >= p.wm_at
                   AND (t.{{QbAttempt.submittedAt}} > p.wm_at OR t.{{QbAttempt.attemptId}} > p.wm_id)))
        ORDER BY t.{{QbAttempt.submittedAt}}, t.{{QbAttempt.attemptId}}
        LIMIT CASE WHEN CAST(:full AS boolean) THEN NULL ELSE CAST(:cap AS bigint) END) a),
picked AS (
    SELECT r.pid, r.aid
    FROM (SELECT c.pid, c.aid, row_number() OVER (ORDER BY c.sat, c.aid) AS rn FROM candidates c) r
    WHERE CAST(:full AS boolean) OR r.rn <= CAST(:cap AS bigint)),
attempts AS (
    SELECT p.pid, t.{{QbAttempt.attemptId}} AS aid, t.{{QbAttempt.submittedAt}} AS sat,
           t.{{QbAttempt.startedAt}} AS started, t.{{QbAttempt.status}} AS v_status, t.{{QbAttempt.result}} AS v_result,
           t.{{QbAttempt.versionId}} AS vid
    FROM picked p
    JOIN {{QbAttempt}} t ON t.{{QbAttempt.attemptId}} = p.aid),
per_publication AS (
    SELECT a.pid,
           count(*) AS attempts,
           count(*) FILTER (WHERE a.v_status = 'SUBMITTED') AS submitted,
           count(*) FILTER (WHERE a.v_status = 'EXPIRED') AS expired,
           coalesce(sum(extract(epoch FROM a.sat - a.started)) FILTER (WHERE a.v_status = 'SUBMITTED'), 0) AS duration
    FROM attempts a
    GROUP BY a.pid),
last_read AS (
    SELECT DISTINCT ON (a.pid) a.pid, a.sat, a.aid
    FROM attempts a
    ORDER BY a.pid, a.sat DESC, a.aid DESC),
per_version AS (
    SELECT a.pid, a.vid, count(*) AS n
    FROM attempts a
    GROUP BY a.pid, a.vid),
per_question AS (
    SELECT a.pid, a.vid, CAST(q.value ->> 'no' AS int) AS qno, count(*) AS n,
           count(*) FILTER (WHERE jsonb_typeof(q.value -> 'earned') = 'number'
                              AND CAST(q.value ->> 'earned' AS numeric) > 0) AS correct
    FROM attempts a
    CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN a.v_result IS JSON ARRAY THEN CAST(a.v_result AS jsonb)
                                                 ELSE CAST('[]' AS jsonb) END) q
    WHERE jsonb_typeof(q.value -> 'no') = 'number'
    GROUP BY a.pid, a.vid, CAST(q.value ->> 'no' AS int))
SELECT
    p.pid                                      AS publicationId,
    v.vid                                      AS versionId,
    CAST(v.n AS numeric(12, 0))                AS vAttempts,
    CAST(q.qno AS numeric(4, 0))               AS questionNo,
    CAST(coalesce(q.n, 0) AS numeric(12, 0))   AS qCompletions,
    CAST(coalesce(q.correct, 0) AS numeric(12, 0)) AS qCorrect,
    CAST(p.attempts AS numeric(12, 0))         AS attempts,
    CAST(p.submitted AS numeric(12, 0))        AS submitted,
    CAST(p.expired AS numeric(12, 0))          AS expired,
    CAST(p.duration AS numeric(15, 3))         AS durationSec,
    l.sat                                      AS lastAt,
    CAST(l.aid AS varchar(36))                 AS lastId
FROM per_publication p
JOIN last_read l ON l.pid = p.pid
JOIN per_version v ON v.pid = p.pid
LEFT JOIN per_question q ON q.pid = v.pid AND q.vid = v.vid
