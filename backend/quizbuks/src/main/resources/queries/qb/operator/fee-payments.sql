/*---
id: qb.operator.fee-payments
description: "F-M1: how the platform's fees were paid, one sponsor's of the caller's operator (another operator's sponsor: no rows) - each part of each payment (the sub-account and the lot it was taken from; negative: given back when an invoice was voided), newest first"
entities: [QbFeePayment, QbFeeInvoice, QbFeeCreditGrant, QbCashLot, QbSponsorOperator, QbOperatorMember]
datasets:
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbCashLot: urn:jabiz:dataset:default:QbCashLot
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
params:
  sponsorId: { like: QbFeePayment.sponsorId, required: true, description: "The sponsor (their user id)" }
results:
  paymentId:     { from: QbFeePayment.paymentId }
  paidAt:        { from: QbFeePayment.paidAt }
  targetEntity:  { from: QbFeePayment.targetEntity }
  targetId:      { from: QbFeePayment.targetId }
  invoiceId:     { kind: { type: text, maxLength: 36 } }
  invoiceNo:     { from: QbFeeInvoice.invoiceNo }
  subAccount:    { from: QbFeePayment.subAccount }
  lotId:         { kind: { type: text, maxLength: 36 } }
  source:        { kind: { type: text, maxLength: 20 } }
  amount:        { from: QbFeePayment.amount }
  transactionId: { from: QbFeePayment.transactionId }
list:
  filters: [subAccount, targetEntity, paidAt]
  sorts:   [paidAt]
  defaultSort: { field: paidAt, asc: false }
  key: [paymentId]
permissions: [qb.operator.use]
---*/
-- The invoice a part paid: the invoice itself, or the one of the charge paid (a prepaid charge's own, or one paid
-- early). The lot's source: where the grant or the cash came from.
SELECT
    p.{{QbFeePayment.paymentId}}    AS paymentId,
    p.{{QbFeePayment.paidAt}}       AS paidAt,
    p.{{QbFeePayment.targetEntity}} AS targetEntity,
    p.{{QbFeePayment.targetId}}     AS targetId,
    CAST(coalesce(ii.{{QbFeeInvoice.invoiceId}}, ci.{{QbFeeInvoice.invoiceId}}) AS varchar(36)) AS invoiceId,
    coalesce(ii.{{QbFeeInvoice.invoiceNo}}, ci.{{QbFeeInvoice.invoiceNo}}) AS invoiceNo,
    p.{{QbFeePayment.subAccount}}   AS subAccount,
    CAST(coalesce(p.{{QbFeePayment.grantId}}, p.{{QbFeePayment.cashLotId}}) AS varchar(36)) AS lotId,
    CAST(coalesce(g.{{QbFeeCreditGrant.source}}, l.{{QbCashLot.source}}) AS varchar(20)) AS source,
    p.{{QbFeePayment.amount}}       AS amount,
    p.{{QbFeePayment.transactionId}} AS transactionId
FROM {{QbFeePayment}} p
LEFT JOIN {{QbFeeInvoice}} ii ON p.{{QbFeePayment.targetEntity}} = 'QbFeeInvoice'
    AND CAST(ii.{{QbFeeInvoice.invoiceId}} AS text) = p.{{QbFeePayment.targetId}}
LEFT JOIN {{QbFeeInvoice}} ci ON p.{{QbFeePayment.targetEntity}} = 'QbFeeCharge'
    AND ci.{{QbFeeInvoice.chargeId}} = p.{{QbFeePayment.targetId}}
LEFT JOIN {{QbFeeCreditGrant}} g ON CAST(g.{{QbFeeCreditGrant.grantId}} AS text) = p.{{QbFeePayment.grantId}}
LEFT JOIN {{QbCashLot}} l ON CAST(l.{{QbCashLot.lotId}} AS text) = p.{{QbFeePayment.cashLotId}}
WHERE true
  AND p.{{QbFeePayment.sponsorId}} = :sponsorId
  AND EXISTS (SELECT 1 FROM {{QbSponsorOperator}} a
              JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
                AND me.{{QbOperatorMember.operatorId}} = a.{{QbSponsorOperator.operatorId}}
              WHERE a.{{QbSponsorOperator.userId}} = :sponsorId)
