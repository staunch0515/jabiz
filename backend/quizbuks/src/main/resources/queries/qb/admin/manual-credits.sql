/*---
id: qb.admin.manual-credits
description: The manual credits (Q18b), newest first - whose wallet, how much, which kind and why, and who recorded it - for the administrators who record and approve them
entities: [QbTopUp, QbSponsor]
datasets:
  QbTopUp: urn:jabiz:dataset:default:QbTopUp
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
results:
  topUpId:           { from: QbTopUp.topUpId }
  sponsorId:         { from: QbTopUp.sponsorId }
  company:           { from: QbSponsor.company }
  transferCode:      { from: QbTopUp.transferCode }
  manualKind:        { from: QbTopUp.manualKind }
  manualReason:      { from: QbTopUp.manualReason }
  amount:            { from: QbTopUp.amount }
  status:            { from: QbTopUp.status }
  createdAt:         { from: QbTopUp.createdAt }
  receivedOn:        { from: QbTopUp.receivedOn }
  bankReference:     { from: QbTopUp.bankReference }
  recordedBy:        { from: QbTopUp.recordedBy }
  approvalRequestId: { from: QbTopUp.approvalRequestId }
  paidAt:            { from: QbTopUp.paidAt }
list:
  filters: [topUpId, sponsorId, status, manualKind, transferCode, createdAt]
  sorts:   [createdAt, paidAt, amount]
  defaultSort: { field: createdAt, asc: false }
  key: [topUpId]
permissions: [qb.topup.bank.approve]
---*/
-- Manual credits only. Both kinds of administrator who take part read it (finance records, a content or another
-- finance administrator approves): the same permission as the bank top-ups', whose approvers approve these too.
SELECT
    t.{{QbTopUp.topUpId}}           AS topUpId,
    t.{{QbTopUp.sponsorId}}         AS sponsorId,
    s.{{QbSponsor.company}}         AS company,
    t.{{QbTopUp.transferCode}}      AS transferCode,
    t.{{QbTopUp.manualKind}}        AS manualKind,
    t.{{QbTopUp.manualReason}}      AS manualReason,
    t.{{QbTopUp.amount}}            AS amount,
    t.{{QbTopUp.status}}            AS status,
    t.{{QbTopUp.createdAt}}         AS createdAt,
    t.{{QbTopUp.receivedOn}}        AS receivedOn,
    t.{{QbTopUp.bankReference}}     AS bankReference,
    t.{{QbTopUp.recordedBy}}        AS recordedBy,
    t.{{QbTopUp.approvalRequestId}} AS approvalRequestId,
    t.{{QbTopUp.paidAt}}            AS paidAt
FROM {{QbTopUp}} t
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = t.{{QbTopUp.sponsorId}}
WHERE t.{{QbTopUp.method}} = 'MANUAL'
