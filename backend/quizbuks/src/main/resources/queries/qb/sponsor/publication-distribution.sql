/*---
id: qb.sponsor.publication-distribution
description: "A Sponsor's publication's distribution fee (F-M6): the price per start frozen at its submission, the starts so far and since its last settlement, and what those would come to now"
entities: [QbPublication, QbPublicationStartCounter, QbDistributionSettlement]
datasets:
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
  QbDistributionSettlement: urn:jabiz:dataset:sponsor:QbDistributionSettlement
params:
  publicationId: { like: QbPublication.publicationId, required: true, description: The publication }
results:
  unit:            { from: QbPublication.distributionUnit }
  atFloor:         { from: QbPublication.distributionAtFloor }
  starts:          { kind: { type: numeric, precision: 12, scale: 0 } }
  settledStarts:   { kind: { type: numeric, precision: 12, scale: 0 } }
  pendingStarts:   { kind: { type: numeric, precision: 12, scale: 0 } }
  charged:         { kind: { type: monetary, currency: JPY, scale: 0 } }
  pendingEstimate: { kind: { type: monetary, currency: JPY, scale: 0 } }
  settlements:     { kind: { type: numeric, precision: 4, scale: 0 } }
  finalSettled:    { kind: { type: bool } }
permissions: [qb.sponsor.finance.read]
---*/
-- The publication through the caller's own view: another Sponsor's has no row; one without a price per start (the fee
-- was off at its submission, or its operator has no price) has a row whose unit is empty. The starts are the
-- publication's own start counter (what the Sponsor's dashboard shows too). pendingEstimate: what the starts since the
-- last settlement come to before tax, rounded as the settlement rounds (the whole so far, less what was charged).
SELECT p.{{QbPublication.distributionUnit}} AS unit,
       p.{{QbPublication.distributionAtFloor}} AS atFloor,
       coalesce(sc.{{QbPublicationStartCounter.starts}}, 0) AS starts,
       coalesce(s.settled, 0) AS settledStarts,
       greatest(coalesce(sc.{{QbPublicationStartCounter.starts}}, 0) - coalesce(s.settled, 0), 0) AS pendingStarts,
       coalesce(s.charged, 0) AS charged,
       CASE WHEN p.{{QbPublication.distributionUnit}} IS NULL OR coalesce(s.final, false) THEN 0
            ELSE greatest(ceil(coalesce(sc.{{QbPublicationStartCounter.starts}}, 0) * p.{{QbPublication.distributionUnit}})
                          - coalesce(s.charged, 0), 0) END AS pendingEstimate,
       coalesce(s.rows, 0) AS settlements,
       coalesce(s.final, false) AS finalSettled
FROM {{QbPublication}} p
LEFT JOIN {{QbPublicationStartCounter}} sc
    ON sc.{{QbPublicationStartCounter.publicationId}} = p.{{QbPublication.publicationId}}
LEFT JOIN LATERAL (SELECT max(d.{{QbDistributionSettlement.toStarts}}) AS settled,
                          sum(d.{{QbDistributionSettlement.amount}}) AS charged,
                          count(*) AS rows,
                          bool_or(d.{{QbDistributionSettlement.final}}) AS final
                   FROM {{QbDistributionSettlement}} d
                   WHERE d.{{QbDistributionSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)) s
    ON true
WHERE p.{{QbPublication.publicationId}} = :publicationId
