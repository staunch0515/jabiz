/*---
id: qb.app.payout-account
description: The taker's payout account (U-53) - its state at Stripe, what Stripe still needs, and the onboarding link while it is valid
entities: [QbPayoutAccount, QbPayoutOnboarding]
datasets:
  QbPayoutAccount: urn:jabiz:dataset:taker:QbPayoutAccount
  QbPayoutOnboarding: urn:jabiz:dataset:taker:QbPayoutOnboarding
params:
  now: { like: QbPayoutOnboarding.expiresAt, required: true, description: The present time (a link past its expiry is not given) }
results:
  status:          { from: QbPayoutAccount.status }
  payoutsEnabled:  { from: QbPayoutAccount.payoutsEnabled }
  requirementsDue: { from: QbPayoutAccount.requirementsDue }
  onboardingUrl:   { from: QbPayoutOnboarding.url }
  linkExpiresAt:   { from: QbPayoutOnboarding.expiresAt }
permissions: [qb.me]
---*/
-- Both datasets are the caller's own: one row at most, none before the first onboarding. Stripe's account id and the
-- bank details are not shown (QuizBuks keeps no bank details: Stripe's pages hold them).
SELECT
    a.{{QbPayoutAccount.status}}          AS status,
    a.{{QbPayoutAccount.payoutsEnabled}}  AS payoutsEnabled,
    a.{{QbPayoutAccount.requirementsDue}} AS requirementsDue,
    o.o_url                               AS onboardingUrl,
    o.o_expires                           AS linkExpiresAt
FROM {{QbPayoutAccount}} a
LEFT JOIN (
    SELECT x.{{QbPayoutOnboarding.url}} AS o_url, x.{{QbPayoutOnboarding.expiresAt}} AS o_expires
    FROM {{QbPayoutOnboarding}} x
    WHERE x.{{QbPayoutOnboarding.expiresAt}} > :now) o ON true
