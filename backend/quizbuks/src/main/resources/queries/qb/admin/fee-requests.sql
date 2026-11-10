/*---
id: qb.admin.fee-requests
description: The requests about the platform's fees waiting for or given another administrator's approval (Q87) - a fee charged by hand, an invoice voided, platform fee Kudos granted by hand (F-M2)
entities: [QbFeeRequest, QbSponsor]
datasets:
  QbFeeRequest: urn:jabiz:dataset:default:QbFeeRequest
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
results:
  requestId:         { from: QbFeeRequest.requestId }
  kind:              { from: QbFeeRequest.kind }
  sponsorId:         { from: QbFeeRequest.sponsorId }
  company:           { from: QbSponsor.company }
  amount:            { from: QbFeeRequest.amount }
  invoiceId:         { from: QbFeeRequest.invoiceId }
  reason:            { from: QbFeeRequest.reason }
  status:            { from: QbFeeRequest.status }
  requestedBy:       { from: QbFeeRequest.requestedBy }
  requestedAt:       { from: QbFeeRequest.requestedAt }
  approvalRequestId: { from: QbFeeRequest.approvalRequestId }
  decidedAt:         { from: QbFeeRequest.decidedAt }
  chargeId:          { from: QbFeeRequest.chargeId }
  validDays:         { from: QbFeeRequest.validDays }
  grantId:           { from: QbFeeRequest.grantId }
list:
  filters: [kind, sponsorId, status, requestedAt]
  sorts:   [requestedAt, status]
  defaultSort: { field: requestedAt, asc: false }
  key: [requestId]
permissions: [qb.topup.bank.approve]
---*/
-- Both kinds of administrator who take part read it (finance records, another administrator approves): the same
-- permission as the bank top-ups', whose approvers approve these too.
SELECT
    r.{{QbFeeRequest.requestId}}         AS requestId,
    r.{{QbFeeRequest.kind}}              AS kind,
    r.{{QbFeeRequest.sponsorId}}         AS sponsorId,
    s.{{QbSponsor.company}}              AS company,
    r.{{QbFeeRequest.amount}}            AS amount,
    r.{{QbFeeRequest.invoiceId}}         AS invoiceId,
    r.{{QbFeeRequest.reason}}            AS reason,
    r.{{QbFeeRequest.status}}            AS status,
    r.{{QbFeeRequest.requestedBy}}       AS requestedBy,
    r.{{QbFeeRequest.requestedAt}}       AS requestedAt,
    r.{{QbFeeRequest.approvalRequestId}} AS approvalRequestId,
    r.{{QbFeeRequest.decidedAt}}         AS decidedAt,
    r.{{QbFeeRequest.chargeId}}          AS chargeId,
    r.{{QbFeeRequest.validDays}}         AS validDays,
    r.{{QbFeeRequest.grantId}}           AS grantId
FROM {{QbFeeRequest}} r
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = r.{{QbFeeRequest.sponsorId}}
