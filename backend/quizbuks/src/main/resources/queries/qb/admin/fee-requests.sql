/*---
id: qb.admin.fee-requests
description: "Q87b: the platform fees charged by hand and the invoices' voidings asked for, newest first, with their state"
entities: [QbFeeRequest, QbSponsor, QbFeeInvoice]
datasets:
  QbFeeRequest: urn:jabiz:dataset:default:QbFeeRequest
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
results:
  requestId:         { from: QbFeeRequest.requestId }
  kind:              { from: QbFeeRequest.kind }
  sponsorId:         { from: QbFeeRequest.sponsorId }
  company:           { from: QbSponsor.company }
  invoiceNo:         { from: QbFeeInvoice.invoiceNo }
  amount:            { from: QbFeeRequest.amount }
  reason:            { from: QbFeeRequest.reason }
  status:            { from: QbFeeRequest.status }
  requestedBy:       { from: QbFeeRequest.requestedBy }
  requestedAt:       { from: QbFeeRequest.requestedAt }
  approvalRequestId: { from: QbFeeRequest.approvalRequestId }
list:
  filters: [status, kind]
  sorts: [requestedAt]
  defaultSort: { field: requestedAt, asc: false }
  key: [requestId]
permissions: [qb.admin.finance.read]
---*/
SELECT r.{{QbFeeRequest.requestId}} AS requestId, r.{{QbFeeRequest.kind}} AS kind,
       r.{{QbFeeRequest.sponsorId}} AS sponsorId, s.{{QbSponsor.company}} AS company,
       i.{{QbFeeInvoice.invoiceNo}} AS invoiceNo, r.{{QbFeeRequest.amount}} AS amount,
       r.{{QbFeeRequest.reason}} AS reason, r.{{QbFeeRequest.status}} AS status,
       r.{{QbFeeRequest.requestedBy}} AS requestedBy, r.{{QbFeeRequest.requestedAt}} AS requestedAt,
       r.{{QbFeeRequest.approvalRequestId}} AS approvalRequestId
FROM {{QbFeeRequest}} r
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = r.{{QbFeeRequest.sponsorId}}
LEFT JOIN {{QbFeeInvoice}} i ON CAST(i.{{QbFeeInvoice.invoiceId}} AS text) = r.{{QbFeeRequest.invoiceId}}
