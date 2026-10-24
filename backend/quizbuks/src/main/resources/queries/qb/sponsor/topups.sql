/*---
id: qb.sponsor.topups
description: The sponsor's own top-ups (M-04), newest first, with the checkout's address while it can still be paid, and a bank transfer's code, report and what was received (Q13a)
entities: [QbTopUp]
datasets:
  QbTopUp: urn:jabiz:dataset:sponsor:QbTopUp
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, description: "The client's now: a checkout expired before it has no address" }
results:
  topUpId:        { from: QbTopUp.topUpId }
  amount:         { from: QbTopUp.amount }
  status:         { from: QbTopUp.status }
  createdAt:      { from: QbTopUp.createdAt }
  expiresAt:      { from: QbTopUp.expiresAt }
  checkoutUrl:    { from: QbTopUp.checkoutUrl }
  paidAt:         { from: QbTopUp.paidAt }
  stripeFee:      { from: QbTopUp.stripeFee }
  refundedAmount: { from: QbTopUp.refundedAmount }
  failureCode:    { from: QbTopUp.failureCode }
  method:          { from: QbTopUp.method }
  transferCode:    { from: QbTopUp.transferCode }
  requestedAmount: { from: QbTopUp.requestedAmount }
  payerName:       { from: QbTopUp.payerName }
  transferDate:    { from: QbTopUp.transferDate }
  reportedAt:      { from: QbTopUp.reportedAt }
  receivedOn:      { from: QbTopUp.receivedOn }
  manualKind:      { from: QbTopUp.manualKind }
list:
  filters: [status, createdAt, paidAt, amount, method]
  sorts:   [createdAt, paidAt, amount]
  defaultSort: { field: createdAt, asc: false }
  key: [topUpId]
permissions: [qb.sponsor.finance.read]
---*/
-- The address only while the top-up is open (and, given the client's now, not expired): a page never sends the
-- sponsor to a checkout that cannot be paid. Taken back includes lost disputes. A bank top-up's day received only once
-- it is paid: under review it may still be rejected, and finance's reference stays finance's. A manual credit (Q18b) is
-- in the sponsor's dataset only once paid; its kind says whether money was received (a receipt) or Kudos granted.
SELECT
    t.{{QbTopUp.topUpId}}   AS topUpId,
    t.{{QbTopUp.amount}}    AS amount,
    t.{{QbTopUp.status}}    AS status,
    t.{{QbTopUp.createdAt}} AS createdAt,
    t.{{QbTopUp.expiresAt}} AS expiresAt,
    CASE WHEN t.{{QbTopUp.status}} = 'OPEN'
              AND (CAST(:at AS timestamptz) IS NULL OR t.{{QbTopUp.expiresAt}} > CAST(:at AS timestamptz))
         THEN t.{{QbTopUp.checkoutUrl}} END AS checkoutUrl,
    t.{{QbTopUp.paidAt}}    AS paidAt,
    t.{{QbTopUp.stripeFee}} AS stripeFee,
    t.{{QbTopUp.refundedAmount}} + t.{{QbTopUp.disputeClawback}} AS refundedAmount,
    t.{{QbTopUp.failureCode}} AS failureCode,
    t.{{QbTopUp.method}} AS method,
    t.{{QbTopUp.transferCode}} AS transferCode,
    t.{{QbTopUp.requestedAmount}} AS requestedAmount,
    t.{{QbTopUp.payerName}} AS payerName,
    t.{{QbTopUp.transferDate}} AS transferDate,
    t.{{QbTopUp.reportedAt}} AS reportedAt,
    CASE WHEN t.{{QbTopUp.status}} = 'PAID' THEN t.{{QbTopUp.receivedOn}} END AS receivedOn,
    t.{{QbTopUp.manualKind}} AS manualKind
FROM {{QbTopUp}} t
