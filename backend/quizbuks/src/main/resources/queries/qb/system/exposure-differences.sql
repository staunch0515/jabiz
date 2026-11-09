/*---
id: qb.system.exposure-differences
description: >-
  F-S13: the daily reconciliation of the exposures: the publications whose successes went beyond their purchased
  exposures by more than the tolerance (at least qb.exposure.reconcile.min-tolerance, else
  qb.exposure.reconcile.tolerance-percent of the purchase rounded up), and the ended ones that counted an exposure more
  than qb.exposure.reconcile.end-grace-sec after their end
entities: [SysParam, QbPublicationCard, QbExposure]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbExposure: urn:jabiz:dataset:default:QbExposure
results:
  publicationId: { from: QbPublicationCard.publicationId }
  purchased:     { from: QbPublicationCard.exposurePurchased }
  successes:     { kind: { type: numeric, precision: 12, scale: 0 } }
  late:          { kind: { type: numeric, precision: 12, scale: 0 } }
list:
  sorts: [publicationId]
  defaultSort: { field: publicationId, asc: true }
  key: [publicationId]
permissions: [qb.publication.system]
---*/
-- Only the cards with a shared video (partial index, V90) can count. The tolerance is exposure.ExposureRules.tolerance
-- with its parameters as ExposureRules reads them (a value not a whole number, or none yet: the declared default);
-- what goes beyond the purchase is never charged (ExposureRules.billable), so a difference asks a person to look, it
-- does not change money.
SELECT c.{{QbPublicationCard.publicationId}} AS publicationId, c.{{QbPublicationCard.exposurePurchased}} AS purchased,
       x.successes AS successes, x.late AS late
FROM {{QbPublicationCard}} c
CROSS JOIN (
    SELECT greatest(0, coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.exposure.reconcile.min-tolerance'
                                              AND CAST(p.{{SysParam.value}} AS text) ~ '^[0-9]{1,12}$'
                                         THEN CAST(CAST(p.{{SysParam.value}} AS text) AS bigint) END), 20)) AS least_n,
           least(100, coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.exposure.reconcile.tolerance-percent'
                                         AND CAST(p.{{SysParam.value}} AS text) ~ '^[0-9]{1,3}$'
                                    THEN CAST(CAST(p.{{SysParam.value}} AS text) AS int) END), 5)) AS pct,
           least(86400, coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.exposure.reconcile.end-grace-sec'
                                           AND CAST(p.{{SysParam.value}} AS text) ~ '^[0-9]{1,5}$'
                                      THEN CAST(CAST(p.{{SysParam.value}} AS text) AS int) END), 60)) AS grace
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} IN ('qb.exposure.reconcile.min-tolerance', 'qb.exposure.reconcile.tolerance-percent',
                                      'qb.exposure.reconcile.end-grace-sec')) k
CROSS JOIN LATERAL (
    SELECT count(*) FILTER (WHERE e.{{QbExposure.countedAt}} IS NOT NULL) AS successes,
           count(*) FILTER (WHERE c.{{QbPublicationCard.status}} = 'ENDED' AND c.{{QbPublicationCard.endedAt}} IS NOT NULL
                              AND e.{{QbExposure.countedAt}} > c.{{QbPublicationCard.endedAt}} + k.grace * interval '1 second') AS late
    FROM {{QbExposure}} e
    WHERE e.{{QbExposure.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)) x
WHERE c.{{QbPublicationCard.sharedVideoId}} IS NOT NULL
  AND (x.late > 0
       OR (c.{{QbPublicationCard.exposurePurchased}} IS NOT NULL
           AND x.successes > c.{{QbPublicationCard.exposurePurchased}}
               + greatest(k.least_n, ceil(c.{{QbPublicationCard.exposurePurchased}} * k.pct / 100.0))))
