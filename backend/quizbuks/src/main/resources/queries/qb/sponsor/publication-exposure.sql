/*---
id: qb.sponsor.publication-exposure
description: "A Sponsor's publication's exposure fee (F-M8): the price per exposure frozen at its submission, the exposures bought, counted and settled so far, what was charged and what the exposures since the last settlement come to now, and the share of the purchase at which the Sponsor is told it is running low"
entities: [QbPublication, QbExposure, QbExposureSettlement, SysParam]
datasets:
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbExposure: urn:jabiz:dataset:default:QbExposure
  QbExposureSettlement: urn:jabiz:dataset:sponsor:QbExposureSettlement
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  publicationId: { like: QbPublication.publicationId, required: true, description: The publication }
results:
  unit:            { from: QbPublication.exposureUnit }
  purchased:       { from: QbPublication.exposurePurchased }
  counted:         { kind: { type: numeric, precision: 12, scale: 0 } }
  settledCount:    { kind: { type: numeric, precision: 12, scale: 0 } }
  charged:         { kind: { type: monetary, currency: JPY, scale: 0 } }
  pendingEstimate: { kind: { type: monetary, currency: JPY, scale: 0 } }
  settlements:     { kind: { type: numeric, precision: 4, scale: 0 } }
  finalSettled:    { kind: { type: bool } }
  lowRatio:        { kind: { type: text, maxLength: 20 } }
permissions: [qb.sponsor.finance.read]
---*/
-- The publication through the caller's own view: another Sponsor's has no row; one that bought nothing (the fee was off
-- at its submission, or its operator has no price) has a row whose unit and purchased are empty. counted: the
-- exposures counted (successes) so far, which the settlement caps at what was bought. pendingEstimate: the whole so far
-- less what was charged, before tax, rounded as the settlement rounds (ceil of the cumulative amount); none once the
-- final settlement is written.
SELECT p.{{QbPublication.exposureUnit}} AS unit, p.{{QbPublication.exposurePurchased}} AS purchased,
       x.counted AS counted, coalesce(s.settled, 0) AS settledCount, coalesce(s.charged, 0) AS charged,
       CASE WHEN p.{{QbPublication.exposureUnit}} IS NULL OR p.{{QbPublication.exposurePurchased}} IS NULL
                 OR coalesce(s.final, false) THEN 0
            ELSE greatest(ceil(least(x.counted, p.{{QbPublication.exposurePurchased}}) * p.{{QbPublication.exposureUnit}})
                          - coalesce(s.charged, 0), 0) END AS pendingEstimate,
       coalesce(s.rows, 0) AS settlements, coalesce(s.final, false) AS finalSettled,
       (SELECT max(r.{{SysParam.value}}) FROM {{SysParam}} r WHERE r.{{SysParam.paramKey}} = 'qb.exposure.low-ratio') AS lowRatio
FROM {{QbPublication}} p
CROSS JOIN LATERAL (SELECT count(*) AS counted FROM {{QbExposure}} e
                    WHERE e.{{QbExposure.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
                      AND e.{{QbExposure.countedAt}} IS NOT NULL) x
LEFT JOIN LATERAL (SELECT max(d.{{QbExposureSettlement.toCount}}) AS settled,
                          sum(d.{{QbExposureSettlement.amount}}) AS charged,
                          count(*) AS rows,
                          bool_or(d.{{QbExposureSettlement.final}}) AS final
                   FROM {{QbExposureSettlement}} d
                   WHERE d.{{QbExposureSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)) s
    ON true
WHERE p.{{QbPublication.publicationId}} = :publicationId
