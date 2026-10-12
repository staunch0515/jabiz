/*---
id: qb.sponsor.topups
description: The sponsor's own top-ups (M-04), newest first, with the checkout's address while it can still be paid
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
list:
  filters: [status, createdAt, paidAt, amount]
  sorts:   [createdAt, paidAt, amount]
  defaultSort: { field: createdAt, asc: false }
  key: [topUpId]
permissions: [qb.sponsor.finance.read]
---*/
-- The address only while the top-up is open (and, given the client's now, not expired): a page never sends the
-- sponsor to a checkout that cannot be paid. Taken back includes lost disputes.
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
    t.{{QbTopUp.failureCode}} AS failureCode
FROM {{QbTopUp}} t
