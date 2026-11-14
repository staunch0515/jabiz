/*---
id: qb.system.operator-settlements-waiting
description: "The operators' settlements that wait (F-M12 §6): each operator's earliest month without a settlement, earlier than the current one, and the steps it still waits for (DISTRIBUTION, INVOICE, EXPOSURE); none when only the daily run is missing. Run by QB_OPERATOR_SETTLEMENTS_WAITING with the server's month and zone (#701 review M2), not by a browser"
entities: [QbOperator, QbOperatorSettlement, QbPublication, QbPublicationCard, QbDistributionSettlement, QbExposureSettlement, QbFeeCharge, QbFeeInvoice]
datasets:
  QbOperator: urn:jabiz:dataset:default:QbOperator
  QbOperatorSettlement: urn:jabiz:dataset:default:QbOperatorSettlement
  QbPublication: urn:jabiz:dataset:default:QbPublication
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbDistributionSettlement: urn:jabiz:dataset:default:QbDistributionSettlement
  QbExposureSettlement: urn:jabiz:dataset:default:QbExposureSettlement
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
params:
  current: { like: QbOperatorSettlement.month, required: true, description: "The current month (yyyy-MM, the fiscal year's zone): earlier months only" }
  zone:    { kind: { type: text, maxLength: 64 }, required: true, description: "The fiscal year's zone (a region name or UTC)" }
results:
  operatorId:   { from: QbOperator.operatorId }
  operatorName: { from: QbOperator.name }
  month:        { from: QbOperatorSettlement.month }
  steps:        { kind: { type: text, maxLength: 64 } }
list:
  sorts: [month, operatorName]
  defaultSort: { field: month, asc: true }
  key: [operatorId]
permissions: [qb.operator.settlement.system]
---*/
-- The head of each operator's line, as qb.system.operator-settlements-due takes it, with the steps the month waits for:
-- the branches of qb.system.operator-settlement-readiness, written the same (OperatorReadinessTemplateTest compares
-- them), with the month's bounds worked out here in the zone. steps is empty when nothing is pending (the daily run
-- has not settled the month yet). :current and :zone come from the server (QB_OPERATOR_SETTLEMENTS_WAITING); the months
-- looked at are still capped at 120 before :current, so that a wrong value cannot make the series long. The edge that
-- remains (#701 re-review L-5): an operator more than ten years behind is listed with the month 120 before the current
-- one, not its earliest; the daily check (OPERATOR_SETTLEMENT_WAITING) still names the true one.
WITH due_months AS (
    SELECT o.{{QbOperator.operatorId}} AS r_operator, m.start_of_month AS r_local_start
    FROM {{QbOperator}} o
    CROSS JOIN LATERAL (
        SELECT COALESCE((SELECT CAST(max(os.{{QbOperatorSettlement.month}}) || '-01' AS timestamp) + interval '1 month'
                         FROM {{QbOperatorSettlement}} os
                         WHERE os.{{QbOperatorSettlement.operatorId}} = o.{{QbOperator.operatorId}}
                           AND os.{{QbOperatorSettlement.coveredFrom}} IS NULL),
                        date_trunc('month', o.{{QbOperator.createdAt}} AT TIME ZONE CAST(:zone AS text))) AS lower_month
    ) lo
    CROSS JOIN LATERAL generate_series(greatest(lo.lower_month,
        CAST(CAST(:current AS text) || '-01' AS timestamp) - interval '120 months'),
        CAST(CAST(:current AS text) || '-01' AS timestamp) - interval '1 month', interval '1 month') AS m(start_of_month)
),
unsettled AS (
    SELECT d.r_operator, to_char(d.r_local_start, 'YYYY-MM') AS r_month
    FROM due_months d
    WHERE NOT EXISTS (SELECT 1 FROM {{QbOperatorSettlement}} s
                      WHERE s.{{QbOperatorSettlement.operatorId}} = d.r_operator
                        AND s.{{QbOperatorSettlement.month}} = to_char(d.r_local_start, 'YYYY-MM'))
),
heads AS (
    SELECT h.r_operator, h.r_month,
           (CAST(h.r_month || '-01' AS timestamp) AT TIME ZONE CAST(:zone AS text)) AS r_from,
           ((CAST(h.r_month || '-01' AS timestamp) + interval '1 month') AT TIME ZONE CAST(:zone AS text)) AS r_to
    FROM (SELECT u.r_operator, min(u.r_month) AS r_month FROM unsettled u GROUP BY u.r_operator) h
)
SELECT b.r_operator AS operatorId, o.{{QbOperator.name}} AS operatorName, b.r_month AS month,
       CAST(b.r_steps AS varchar(64)) AS steps
FROM (
    SELECT b.*, concat_ws(',',
       CASE WHEN EXISTS (
           SELECT 1
           FROM {{QbPublicationCard}} pc
           JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = pc.{{QbPublicationCard.publicationId}}
           WHERE pc.{{QbPublicationCard.operatorId}} = b.r_operator
             AND p.{{QbPublication.billingOperatorId}} = b.r_operator
             AND p.{{QbPublication.distributionUnit}} IS NOT NULL
             AND p.{{QbPublication.publisherKind}} = 'SPONSOR'
             AND greatest(pc.{{QbPublicationCard.startAt}}, p.{{QbPublication.approvedAt}}) < b.r_to
             AND (pc.{{QbPublicationCard.endedAt}} IS NULL OR pc.{{QbPublicationCard.endedAt}} >= b.r_from)
             AND NOT EXISTS (SELECT 1 FROM {{QbDistributionSettlement}} s
                             WHERE s.{{QbDistributionSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
                               AND (s.{{QbDistributionSettlement.final}} OR s.{{QbDistributionSettlement.period}} = b.r_month)))
           THEN 'DISTRIBUTION' END,
       CASE WHEN EXISTS (
           SELECT 1
           FROM {{QbFeeCharge}} c
           WHERE c.{{QbFeeCharge.operatorId}} = b.r_operator
             AND c.{{QbFeeCharge.period}} = b.r_month
             AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
             AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
                             WHERE i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                               AND ((i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}} AND i.{{QbFeeInvoice.chargeId}} IS NULL)
                                    OR i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))))
           THEN 'INVOICE' END,
       CASE WHEN EXISTS (
           SELECT 1
           FROM {{QbPublicationCard}} pc
           JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = pc.{{QbPublicationCard.publicationId}}
           WHERE pc.{{QbPublicationCard.operatorId}} = b.r_operator
             AND p.{{QbPublication.billingOperatorId}} = b.r_operator
             AND p.{{QbPublication.exposureUnit}} IS NOT NULL
             AND p.{{QbPublication.exposurePurchased}} IS NOT NULL
             AND p.{{QbPublication.publisherKind}} = 'SPONSOR'
             AND greatest(pc.{{QbPublicationCard.startAt}}, p.{{QbPublication.approvedAt}}) < b.r_to
             AND (pc.{{QbPublicationCard.endedAt}} IS NULL OR pc.{{QbPublicationCard.endedAt}} >= b.r_from)
             AND NOT EXISTS (SELECT 1 FROM {{QbExposureSettlement}} s
                             WHERE s.{{QbExposureSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
                               AND (s.{{QbExposureSettlement.final}} OR s.{{QbExposureSettlement.period}} = b.r_month)))
           THEN 'EXPOSURE' END) AS r_steps
    FROM heads b
) b
JOIN {{QbOperator}} o ON o.{{QbOperator.operatorId}} = b.r_operator
