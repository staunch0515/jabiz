/*---
id: qb.system.operator-settlement-readiness
description: "The steps before an operator's settlement of a month that are not done yet: a distribution fee due for one of its publications, an invoice of its sponsors' postpaid fees (F-M12 b)"
entities: [QbPublication, QbPublicationCard, QbDistributionSettlement, QbFeeCharge, QbFeeInvoice]
datasets:
  QbPublication: urn:jabiz:dataset:default:QbPublication
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbDistributionSettlement: urn:jabiz:dataset:default:QbDistributionSettlement
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
params:
  operatorId:  { like: QbFeeCharge.operatorId, required: true, description: The operator }
  month:       { like: QbDistributionSettlement.period, required: true, description: "The settlement's month (yyyy-MM, the fiscal year's zone)" }
  periodStart: { like: QbDistributionSettlement.settledAt, required: true, description: "The month's first instant" }
  periodEnd:   { like: QbDistributionSettlement.settledAt, required: true, description: "The next month's first instant" }
results:
  blockedBy: { kind: { type: text, maxLength: 32 } }
list:
  sorts: [blockedBy]
  defaultSort: { field: blockedBy, asc: true }
  key: [blockedBy]
permissions: [qb.operator.settlement.system]
---*/
-- One row for each step the settlement still waits for; no row: ready. "The operator's sponsors" are those whose
-- publications and fees have this operator frozen on them (billingOperatorId, QbFeeCharge.operatorId), not the
-- operator a sponsor belongs to now. The item waits for these only until readiness-max-days after the month's end.
--
-- DISTRIBUTION: a publication of the operator due a distribution fee for the month, without the month's row or its
-- final one. The condition is the one of qb.system.distribution-due, qb.system.fee-invoices-due and
-- qb.system.recon-prepayments (DistributionDueConditionTest): change them together.
SELECT 'DISTRIBUTION' AS blockedBy
WHERE EXISTS (
    SELECT 1
    FROM {{QbPublicationCard}} pc
    JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = pc.{{QbPublicationCard.publicationId}}
    -- Started from the card (an ordinary table that froze the operator when the publication went on, indexed by it), not
    -- from the temporal publication by a field that can change; the publication's billing operator must agree.
    WHERE pc.{{QbPublicationCard.operatorId}} = :operatorId
      AND p.{{QbPublication.billingOperatorId}} = :operatorId
      AND p.{{QbPublication.distributionUnit}} IS NOT NULL
      AND p.{{QbPublication.publisherKind}} = 'SPONSOR'
      -- When it went on (N1): greatest(its start, its approval); the same expression in qb.system.distribution-due, qb.system.fee-invoices-due and qb.system.recon-prepayments (DistributionDueConditionTest).
      AND greatest(pc.{{QbPublicationCard.startAt}}, p.{{QbPublication.approvedAt}}) < :periodEnd
      AND (pc.{{QbPublicationCard.endedAt}} IS NULL OR pc.{{QbPublicationCard.endedAt}} >= :periodStart)
      AND NOT EXISTS (SELECT 1 FROM {{QbDistributionSettlement}} s
                      WHERE s.{{QbDistributionSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
                        AND (s.{{QbDistributionSettlement.final}} OR s.{{QbDistributionSettlement.period}} = :month)))
UNION ALL
-- INVOICE: a postpaid fee of the month charged with this operator frozen and not on a monthly invoice yet (the owed
-- condition of qb.system.fee-invoices-due: its month's invoice, or an invoice of its own for a fee paid early).
SELECT 'INVOICE' AS blockedBy
WHERE EXISTS (
    SELECT 1
    FROM {{QbFeeCharge}} c
    WHERE c.{{QbFeeCharge.operatorId}} = :operatorId
      AND c.{{QbFeeCharge.period}} = :month
      AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
      AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
                      WHERE i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                        AND ((i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}} AND i.{{QbFeeInvoice.chargeId}} IS NULL)
                             OR i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))))
-- READINESS EXTENSION POINT (F-M12 b): the exposure fee's monthly settlement (F-M8's QB_EXPOSURE_SETTLE; its table
-- qb_exposure_settlement_version exists, the process does not yet) and F-M9 (the AI monthly charge) are not merged. When
-- they merge, each adds its step as one more branch here, "UNION ALL SELECT 'EXPOSURE' AS blockedBy WHERE EXISTS (...)"
-- and "'AI'" (F-M9: a finished CREDIT AI job of the operator's sponsors, in the month, with no fee yet), with its
-- entity and dataset in the header, and OperatorSettlementReadinessTest names the branch.
