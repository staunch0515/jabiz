/*---
id: qb.system.video-use-differences
description: >-
  F-S14: the daily reconciliation of shared videos' uses: the finalised figures that are not what the counting tables
  give up to the same cut-off
entities: [QbVideoUseFigures, QbExposure, QbAttempt]
datasets:
  QbVideoUseFigures: urn:jabiz:dataset:default:QbVideoUseFigures
  QbExposure: urn:jabiz:dataset:default:QbExposure
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
results:
  publicationId:    { from: QbVideoUseFigures.publicationId }
  successes:        { from: QbVideoUseFigures.successes }
  countedSuccesses: { kind: { type: numeric, precision: 12, scale: 0 } }
  goes:             { from: QbVideoUseFigures.goes }
  countedGoes:      { kind: { type: numeric, precision: 12, scale: 0 } }
list:
  sorts: [publicationId]
  defaultSort: { field: publicationId, asc: true }
  key: [publicationId]
permissions: [qb.publication.system]
---*/
-- The same counts as qb.system.video-use-count (videouse.VideoUseRules.reconciles): any of the four differing is a
-- difference, which asks a person to look.
SELECT f.{{QbVideoUseFigures.publicationId}} AS publicationId,
       f.{{QbVideoUseFigures.successes}} AS successes, x.n_successes AS countedSuccesses,
       f.{{QbVideoUseFigures.goes}} AS goes, x.n_goes AS countedGoes
FROM {{QbVideoUseFigures}} f
CROSS JOIN LATERAL (
    SELECT
        count(*) FILTER (WHERE e.{{QbExposure.openedAt}} <= f.{{QbVideoUseFigures.cutoffAt}}) AS n_opens,
        count(*) FILTER (WHERE e.{{QbExposure.countedAt}} <= f.{{QbVideoUseFigures.cutoffAt}}) AS n_successes,
        count(*) FILTER (WHERE e.{{QbExposure.wentToAnswerAt}} <= f.{{QbVideoUseFigures.cutoffAt}}) AS n_goes,
        count(*) FILTER (WHERE e.{{QbExposure.wentToAnswerAt}} <= f.{{QbVideoUseFigures.cutoffAt}}
            AND EXISTS (SELECT 1 FROM {{QbAttempt}} a
                        WHERE CAST(a.{{QbAttempt.publicationId}} AS text) = e.{{QbExposure.publicationId}}
                          AND a.{{QbAttempt.userId}} = e.{{QbExposure.userId}}
                          AND a.{{QbAttempt.passed}} = true
                          AND a.{{QbAttempt.submittedAt}} <= f.{{QbVideoUseFigures.cutoffAt}})) AS n_qualified
    FROM {{QbExposure}} e
    WHERE e.{{QbExposure.publicationId}} = f.{{QbVideoUseFigures.publicationId}}) x
WHERE f.{{QbVideoUseFigures.finalisedAt}} IS NOT NULL
  AND (f.{{QbVideoUseFigures.opens}} <> x.n_opens OR f.{{QbVideoUseFigures.successes}} <> x.n_successes
       OR f.{{QbVideoUseFigures.goes}} <> x.n_goes OR f.{{QbVideoUseFigures.qualified}} <> x.n_qualified)
