/*---
id: qb.admin.topups
description: Every sponsor's top-ups, newest first, with Stripe's references, the fee and what was taken back (no checkout address), and a bank transfer's code, report and record (Q13a)
entities: [QbTopUp, QbSponsor]
datasets:
  QbTopUp: urn:jabiz:dataset:default:QbTopUp
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
results:
  topUpId:              { from: QbTopUp.topUpId }
  sponsorId:            { from: QbTopUp.sponsorId }
  company:              { from: QbSponsor.company }
  amount:               { from: QbTopUp.amount }
  status:               { from: QbTopUp.status }
  createdAt:            { from: QbTopUp.createdAt }
  paidAt:               { from: QbTopUp.paidAt }
  livemode:             { from: QbTopUp.livemode }
  paymentIntentId:      { from: QbTopUp.paymentIntentId }
  chargeId:             { from: QbTopUp.chargeId }
  balanceTransactionId: { from: QbTopUp.balanceTransactionId }
  stripeFee:            { from: QbTopUp.stripeFee }
  netAmount:            { from: QbTopUp.netAmount }
  presentmentCurrency:  { from: QbTopUp.presentmentCurrency }
  presentmentAmount:    { from: QbTopUp.presentmentAmount }
  fxRate:               { from: QbTopUp.fxRate }
  refundedAmount:       { from: QbTopUp.refundedAmount }
  refundShortfall:      { from: QbTopUp.refundShortfall }
  disputeStatus:        { from: QbTopUp.disputeStatus }
  disputeClawback:      { from: QbTopUp.disputeClawback }
  disputeShortfall:     { from: QbTopUp.disputeShortfall }
  disputeHeld:          { from: QbTopUp.disputeHeld }
  failureCode:          { from: QbTopUp.failureCode }
  checkReason:          { from: QbTopUp.checkReason }
  ledgerTransactionId:  { from: QbTopUp.ledgerTransactionId }
  method:               { from: QbTopUp.method }
  transferCode:         { from: QbTopUp.transferCode }
  requestedAmount:      { from: QbTopUp.requestedAmount }
  payerName:            { from: QbTopUp.payerName }
  transferDate:         { from: QbTopUp.transferDate }
  reportedAt:           { from: QbTopUp.reportedAt }
  receivedOn:           { from: QbTopUp.receivedOn }
  bankReference:        { from: QbTopUp.bankReference }
  recordedBy:           { from: QbTopUp.recordedBy }
  recordedAt:           { from: QbTopUp.recordedAt }
  approvalRequestId:    { from: QbTopUp.approvalRequestId }
list:
  filters: [topUpId, sponsorId, status, createdAt, paidAt, amount, checkReason, disputeStatus, paymentIntentId,
    method, transferCode]
  sorts:   [createdAt, paidAt, amount]
  defaultSort: { field: createdAt, asc: false }
  key: [topUpId]
permissions: [qb.admin.finance.read]
---*/
SELECT
    t.{{QbTopUp.topUpId}}              AS topUpId,
    t.{{QbTopUp.sponsorId}}            AS sponsorId,
    s.{{QbSponsor.company}}            AS company,
    t.{{QbTopUp.amount}}               AS amount,
    t.{{QbTopUp.status}}               AS status,
    t.{{QbTopUp.createdAt}}            AS createdAt,
    t.{{QbTopUp.paidAt}}               AS paidAt,
    t.{{QbTopUp.livemode}}             AS livemode,
    t.{{QbTopUp.paymentIntentId}}      AS paymentIntentId,
    t.{{QbTopUp.chargeId}}             AS chargeId,
    t.{{QbTopUp.balanceTransactionId}} AS balanceTransactionId,
    t.{{QbTopUp.stripeFee}}            AS stripeFee,
    t.{{QbTopUp.netAmount}}            AS netAmount,
    t.{{QbTopUp.presentmentCurrency}}  AS presentmentCurrency,
    t.{{QbTopUp.presentmentAmount}}    AS presentmentAmount,
    t.{{QbTopUp.fxRate}}               AS fxRate,
    t.{{QbTopUp.refundedAmount}}       AS refundedAmount,
    t.{{QbTopUp.refundShortfall}}      AS refundShortfall,
    t.{{QbTopUp.disputeStatus}}        AS disputeStatus,
    t.{{QbTopUp.disputeClawback}}      AS disputeClawback,
    t.{{QbTopUp.disputeShortfall}}     AS disputeShortfall,
    t.{{QbTopUp.disputeHeld}}          AS disputeHeld,
    t.{{QbTopUp.failureCode}}          AS failureCode,
    t.{{QbTopUp.checkReason}}          AS checkReason,
    t.{{QbTopUp.ledgerTransactionId}}  AS ledgerTransactionId,
    t.{{QbTopUp.method}}               AS method,
    t.{{QbTopUp.transferCode}}         AS transferCode,
    t.{{QbTopUp.requestedAmount}}      AS requestedAmount,
    t.{{QbTopUp.payerName}}            AS payerName,
    t.{{QbTopUp.transferDate}}         AS transferDate,
    t.{{QbTopUp.reportedAt}}           AS reportedAt,
    t.{{QbTopUp.receivedOn}}           AS receivedOn,
    t.{{QbTopUp.bankReference}}        AS bankReference,
    t.{{QbTopUp.recordedBy}}           AS recordedBy,
    t.{{QbTopUp.recordedAt}}           AS recordedAt,
    t.{{QbTopUp.approvalRequestId}}    AS approvalRequestId
FROM {{QbTopUp}} t
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = t.{{QbTopUp.sponsorId}}
