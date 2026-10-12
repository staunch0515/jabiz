/*---
id: qb.admin.payout-accounts
description: Every taker's payout account - its state at Stripe and what Stripe still needs
entities: [QbPayoutAccount]
datasets:
  QbPayoutAccount: urn:jabiz:dataset:default:QbPayoutAccount
results:
  payoutAccountId:   { from: QbPayoutAccount.payoutAccountId }
  userId:            { from: QbPayoutAccount.userId }
  externalAccountId: { from: QbPayoutAccount.externalAccountId }
  country:           { from: QbPayoutAccount.country }
  status:            { from: QbPayoutAccount.status }
  payoutsEnabled:    { from: QbPayoutAccount.payoutsEnabled }
  requirementsDue:   { from: QbPayoutAccount.requirementsDue }
  stripeUpdatedAt:   { from: QbPayoutAccount.stripeUpdatedAt }
  createdAt:         { from: QbPayoutAccount.createdAt }
list:
  filters: [userId, status, createdAt]
  sorts:   [createdAt, stripeUpdatedAt]
  defaultSort: { field: createdAt, asc: false }
  key: [payoutAccountId]
permissions: [qb.admin.finance.read]
---*/
SELECT
    a.{{QbPayoutAccount.payoutAccountId}}   AS payoutAccountId,
    a.{{QbPayoutAccount.userId}}            AS userId,
    a.{{QbPayoutAccount.externalAccountId}} AS externalAccountId,
    a.{{QbPayoutAccount.country}}           AS country,
    a.{{QbPayoutAccount.status}}            AS status,
    a.{{QbPayoutAccount.payoutsEnabled}}    AS payoutsEnabled,
    a.{{QbPayoutAccount.requirementsDue}}   AS requirementsDue,
    a.{{QbPayoutAccount.stripeUpdatedAt}}   AS stripeUpdatedAt,
    a.{{QbPayoutAccount.createdAt}}         AS createdAt
FROM {{QbPayoutAccount}} a
