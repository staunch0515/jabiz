/*---
id: qb.system.fee-invoices-due
description: The sponsors with postpaid platform fees of an earlier month and no invoice of it yet, once every distribution fee and exposure fee due for the month is settled; each sponsor's earliest such month (the items of the daily job qb.fee-invoice, Q87, F-M6)
entities: [QbFeeCharge, QbFeeInvoice, QbPublication, QbPublicationCard, QbDistributionSettlement, QbExposureSettlement]
datasets:
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbPublication: urn:jabiz:dataset:default:QbPublication
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbDistributionSettlement: urn:jabiz:dataset:default:QbDistributionSettlement
  QbExposureSettlement: urn:jabiz:dataset:default:QbExposureSettlement
params:
  current: { like: QbFeeCharge.period, required: true, description: "The month of the run (yyyy-MM, the fiscal year's zone): earlier months only" }
  zone:    { kind: { type: text, maxLength: 64 }, required: true, description: "The fiscal year's zone (a region name or UTC)" }
results:
  sponsorId: { from: QbFeeCharge.sponsorId }
  period:    { from: QbFeeCharge.period }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.fee.system]
---*/
-- F-M6 (F-M.md §9.6): a month's invoice waits until every publication of the sponsor due to be settled for the month
-- (the condition of qb.system.distribution-due) has the month's row or its final one; one settled two months late
-- lets the next run issue it. A sponsor's earliest month only (the item key is the sponsor): a later one is issued by
-- the next day's run. The item reads again under the sponsor's wallet lock and issues nothing when the month's invoice
-- is there meanwhile.
WITH owed AS (
    SELECT c.{{QbFeeCharge.sponsorId}} AS sponsor_id, c.{{QbFeeCharge.period}} AS period
    FROM {{QbFeeCharge}} c
    WHERE c.{{QbFeeCharge.period}} < :current
      AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
      AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
                      WHERE i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                        AND ((i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}} AND i.{{QbFeeInvoice.chargeId}} IS NULL)
                             -- Paid early: on an invoice of its own.
                             OR i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)))
    GROUP BY c.{{QbFeeCharge.sponsorId}}, c.{{QbFeeCharge.period}}
), ready AS (
    SELECT o.sponsor_id, o.period
    FROM owed o
    WHERE NOT EXISTS (
        SELECT 1
        FROM {{QbPublication}} p
        JOIN {{QbPublicationCard}} pc ON pc.{{QbPublicationCard.publicationId}} = p.{{QbPublication.publicationId}}
        WHERE p.{{QbPublication.publisherId}} = o.sponsor_id
          AND p.{{QbPublication.distributionUnit}} IS NOT NULL
          AND p.{{QbPublication.publisherKind}} = 'SPONSOR'
          -- When it went on (N1): greatest(its start, its approval); the same expression in qb.system.distribution-due, qb.system.fee-invoices-due and qb.system.recon-fee-account (DistributionDueConditionTest).
          AND greatest(pc.{{QbPublicationCard.startAt}}, p.{{QbPublication.approvedAt}})
              < (CAST(o.period || '-01' AS date) + interval '1 month') AT TIME ZONE :zone
          AND (pc.{{QbPublicationCard.endedAt}} IS NULL
               OR pc.{{QbPublicationCard.endedAt}} >= CAST(o.period || '-01' AS timestamp) AT TIME ZONE :zone)
          AND NOT EXISTS (SELECT 1 FROM {{QbDistributionSettlement}} s
                          WHERE s.{{QbDistributionSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
                            AND (s.{{QbDistributionSettlement.final}}
                                 OR s.{{QbDistributionSettlement.period}} = o.period)))
      -- F-M8: and every exposure fee due for the month (the condition of qb.system.exposure-due).
      AND NOT EXISTS (
        SELECT 1
        FROM {{QbPublication}} p
        JOIN {{QbPublicationCard}} pc ON pc.{{QbPublicationCard.publicationId}} = p.{{QbPublication.publicationId}}
        WHERE p.{{QbPublication.publisherId}} = o.sponsor_id
          AND p.{{QbPublication.exposureUnit}} IS NOT NULL
          AND p.{{QbPublication.exposurePurchased}} IS NOT NULL
          AND p.{{QbPublication.publisherKind}} = 'SPONSOR'
          AND greatest(pc.{{QbPublicationCard.startAt}}, p.{{QbPublication.approvedAt}})
              < (CAST(o.period || '-01' AS date) + interval '1 month') AT TIME ZONE :zone
          AND (pc.{{QbPublicationCard.endedAt}} IS NULL
               OR pc.{{QbPublicationCard.endedAt}} >= CAST(o.period || '-01' AS timestamp) AT TIME ZONE :zone)
          AND NOT EXISTS (SELECT 1 FROM {{QbExposureSettlement}} x
                          WHERE x.{{QbExposureSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
                            AND (x.{{QbExposureSettlement.final}}
                                 OR x.{{QbExposureSettlement.period}} = o.period)))
)
SELECT r.sponsor_id AS sponsorId, min(r.period) AS period
FROM ready r
GROUP BY r.sponsor_id
