/*---
id: qb.system.video-use-count
description: >-
  F-S14: a publication's figures of another's shared video, from the counting tables up to a cut-off: the pushes
  opened, the exposure successes, the takers who went on to answer and those of them who passed in an attempt submitted
  by then (QB_VIDEO_USE_FIGURES_REFRESH writes them; the reconciliation counts the finalised again)
entities: [QbExposure, QbAttempt]
datasets:
  QbExposure: urn:jabiz:dataset:default:QbExposure
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
params:
  publicationId: { kind: { type: text, maxLength: 36 }, required: true, description: The publication }
  cutoff:        { kind: { type: temporal, role: EVENT_TIME }, required: true, description: Counted up to this time }
results:
  opens:     { kind: { type: numeric, precision: 12, scale: 0 } }
  successes: { kind: { type: numeric, precision: 12, scale: 0 } }
  goes:      { kind: { type: numeric, precision: 12, scale: 0 } }
  qualified: { kind: { type: numeric, precision: 12, scale: 0 } }
permissions: [qb.publication.system]
---*/
-- One row per publication and taker in qb_exposure (its unique index), each time set once: the counts up to a cut-off
-- never change afterwards, so finalised figures can always be counted again (videouse.VideoUseRules.reconciles).
-- Qualified: a taker who went on to answer and passed in an attempt submitted by the cut-off.
SELECT
    count(*) FILTER (WHERE e.{{QbExposure.openedAt}} <= CAST(:cutoff AS timestamptz)) AS opens,
    count(*) FILTER (WHERE e.{{QbExposure.countedAt}} <= CAST(:cutoff AS timestamptz)) AS successes,
    count(*) FILTER (WHERE e.{{QbExposure.wentToAnswerAt}} <= CAST(:cutoff AS timestamptz)) AS goes,
    count(*) FILTER (WHERE e.{{QbExposure.wentToAnswerAt}} <= CAST(:cutoff AS timestamptz)
        AND EXISTS (SELECT 1 FROM {{QbAttempt}} a
                    WHERE CAST(a.{{QbAttempt.publicationId}} AS text) = e.{{QbExposure.publicationId}}
                      AND a.{{QbAttempt.userId}} = e.{{QbExposure.userId}}
                      AND a.{{QbAttempt.passed}} = true
                      AND a.{{QbAttempt.submittedAt}} <= CAST(:cutoff AS timestamptz))) AS qualified
FROM {{QbExposure}} e
WHERE e.{{QbExposure.publicationId}} = CAST(:publicationId AS text)
