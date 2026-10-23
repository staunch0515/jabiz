/*---
id: qb.admin.bank-topups
description: The bank top-ups (Q13a), newest first - what the sponsor asked for and reported, and what finance recorded - for the administrators who record and approve them
entities: [QbTopUp, QbSponsor]
datasets:
  QbTopUp: urn:jabiz:dataset:default:QbTopUp
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
results:
  topUpId:           { from: QbTopUp.topUpId }
  sponsorId:         { from: QbTopUp.sponsorId }
  company:           { from: QbSponsor.company }
  transferCode:      { from: QbTopUp.transferCode }
  requestedAmount:   { from: QbTopUp.requestedAmount }
  amount:            { from: QbTopUp.amount }
  status:            { from: QbTopUp.status }
  createdAt:         { from: QbTopUp.createdAt }
  payerName:         { from: QbTopUp.payerName }
  transferDate:      { from: QbTopUp.transferDate }
  reportedAt:        { from: QbTopUp.reportedAt }
  receivedOn:        { from: QbTopUp.receivedOn }
  bankReference:     { from: QbTopUp.bankReference }
  recordedBy:        { from: QbTopUp.recordedBy }
  recordedAt:        { from: QbTopUp.recordedAt }
  approvalRequestId: { from: QbTopUp.approvalRequestId }
  paidAt:            { from: QbTopUp.paidAt }
list:
  filters: [topUpId, sponsorId, status, transferCode, createdAt]
  sorts:   [createdAt, reportedAt, recordedAt]
  defaultSort: { field: createdAt, asc: false }
  key: [topUpId]
permissions: [qb.topup.bank.approve]
---*/
-- Bank top-ups only, and only what recording and approving one needs: no card payment's references. Both kinds of
-- administrator who take part read it (finance records, a content or another finance administrator approves).
SELECT
    t.{{QbTopUp.topUpId}}           AS topUpId,
    t.{{QbTopUp.sponsorId}}         AS sponsorId,
    s.{{QbSponsor.company}}         AS company,
    t.{{QbTopUp.transferCode}}      AS transferCode,
    t.{{QbTopUp.requestedAmount}}   AS requestedAmount,
    t.{{QbTopUp.amount}}            AS amount,
    t.{{QbTopUp.status}}            AS status,
    t.{{QbTopUp.createdAt}}         AS createdAt,
    t.{{QbTopUp.payerName}}         AS payerName,
    t.{{QbTopUp.transferDate}}      AS transferDate,
    t.{{QbTopUp.reportedAt}}        AS reportedAt,
    t.{{QbTopUp.receivedOn}}        AS receivedOn,
    t.{{QbTopUp.bankReference}}     AS bankReference,
    t.{{QbTopUp.recordedBy}}        AS recordedBy,
    t.{{QbTopUp.recordedAt}}        AS recordedAt,
    t.{{QbTopUp.approvalRequestId}} AS approvalRequestId,
    t.{{QbTopUp.paidAt}}            AS paidAt
FROM {{QbTopUp}} t
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = t.{{QbTopUp.sponsorId}}
WHERE t.{{QbTopUp.method}} = 'BANK'
