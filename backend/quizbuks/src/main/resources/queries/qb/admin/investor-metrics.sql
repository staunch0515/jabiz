/*---
id: qb.admin.investor-metrics
description: >-
  The platform's monthly figures for investors (Q27): merchants, takers, attempts, prizes, invitations and money,
  one row per UTC month of the period, oldest first; a month with nothing is a row of zeros. At most 36 months: later
  months of a longer period are left out (the admin page refuses such a period)
entities: [QbSponsor, QbProfile, QbAttempt, QbPublicationCard, QbRedemption, QbDigitalPrize, QbInvitation, QbTopUp]
datasets:
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbProfile: urn:jabiz:dataset:default:QbProfile
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbRedemption: urn:jabiz:dataset:default:QbRedemption
  QbDigitalPrize: urn:jabiz:dataset:default:QbDigitalPrize
  QbInvitation: urn:jabiz:dataset:default:QbInvitation
  QbTopUp: urn:jabiz:dataset:default:QbTopUp
params:
  from: { like: QbAttempt.startedAt, required: true, description: "The first month's first instant (UTC)" }
  to:   { like: QbAttempt.startedAt, required: true, description: "The instant after the last month (UTC); at most 36 months after from" }
results:
  month:              { kind: { type: text, maxLength: 7 } }
  newSponsors:        { kind: { type: numeric, precision: 9, scale: 0 } }
  activeSponsors:     { kind: { type: numeric, precision: 9, scale: 0 } }
  retainedSponsors:   { kind: { type: numeric, precision: 9, scale: 0 } }
  publicationsWentOn: { kind: { type: numeric, precision: 9, scale: 0 } }
  newTakers:          { kind: { type: numeric, precision: 9, scale: 0 } }
  activeTakers:       { kind: { type: numeric, precision: 9, scale: 0 } }
  attempts:           { kind: { type: numeric, precision: 9, scale: 0 } }
  completions:        { kind: { type: numeric, precision: 9, scale: 0 } }
  passes:             { kind: { type: numeric, precision: 9, scale: 0 } }
  prizesWon:          { kind: { type: numeric, precision: 9, scale: 0 } }
  prizesClaimed:      { kind: { type: numeric, precision: 9, scale: 0 } }
  prizesRedeemed:     { kind: { type: numeric, precision: 9, scale: 0 } }
  invitationsSent:    { kind: { type: numeric, precision: 9, scale: 0 } }
  invitationsAnswered: { kind: { type: numeric, precision: 9, scale: 0 } }
  topUps:             { kind: { type: numeric, precision: 9, scale: 0 } }
  topUpAmount:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  rewardsPaid:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  commission:         { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [month]
  defaultSort: { field: month, asc: true }
  key: [month]
permissions: [qb.admin.finance.read]
report:
  period: { from: from, to: to }
  landscape: true
---*/
-- Each figure is counted by the time the thing happened, in the month it falls in (UTC months, as the monthly
-- reports): a merchant is new when their onboarding was approved, active when one of their publications was
-- attempted, retained when active this month and three months earlier; a taker is new when their profile was made
-- (the welcome step; a profile made by a merchant's application is not a taker's; one who applies later still was),
-- active when they started an attempt; attempts by their start, completions and passes by their
-- submission (a run-out attempt counts as a completion, not a pass unless it passed); prizes won by the redemption's
-- creation (and, Q33, a digital prize's), collected by that time (a digital prize by its delivery), claimed by the claim still standing or completed (a declined or lapsed claim is
-- undone, RedemptionProcesses.dueAgain, and no longer counts); invitations by their last send and their answer; top-ups by their
-- payment; rewards paid and the commission by the payment of the reward (Kudos rewards only; cash and prizes are the
-- merchant's own money and are counted above as attempts and prizes). The current version of each temporal record.
-- The bounds are converted, not the columns, so that the indexes on the time columns serve.
WITH months AS (
    SELECT m AS month_start, m + interval '1 month' AS month_end
    FROM generate_series(date_trunc('month', CAST(:from AS timestamptz) AT TIME ZONE 'UTC'),
                         date_trunc('month', (CAST(:to AS timestamptz) - interval '1 second') AT TIME ZONE 'UTC'),
                         interval '1 month') AS g(m)
    WHERE m < date_trunc('month', CAST(:from AS timestamptz) AT TIME ZONE 'UTC') + interval '36 months'
),
active AS (
    SELECT DISTINCT date_trunc('month', a.{{QbAttempt.startedAt}} AT TIME ZONE 'UTC') AS m, a.{{QbAttempt.publisherId}} AS s
    FROM {{QbAttempt}} a
    -- S0: active merchants of the sponsors' system only.
    WHERE a.{{QbAttempt.publisherKind}} = 'SPONSOR'
      AND a.{{QbAttempt.startedAt}} >= (date_trunc('month', CAST(:from AS timestamptz) AT TIME ZONE 'UTC')
        - interval '3 months') AT TIME ZONE 'UTC'
      AND a.{{QbAttempt.startedAt}} < :to
)
SELECT
    to_char(mo.month_start, 'YYYY-MM') AS month,
    (SELECT count(*) FROM {{QbSponsor}} sp
     WHERE sp.{{QbSponsor.reviewStatus}} = 'APPROVED'
       AND sp.{{QbSponsor.approvedAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND sp.{{QbSponsor.approvedAt}} < mo.month_end AT TIME ZONE 'UTC') AS newSponsors,
    (SELECT count(*) FROM active ac WHERE ac.m = mo.month_start) AS activeSponsors,
    (SELECT count(*) FROM active ac
     WHERE ac.m = mo.month_start
       AND EXISTS (SELECT 1 FROM active e WHERE e.s = ac.s AND e.m = mo.month_start - interval '3 months'))
        AS retainedSponsors,
    (SELECT count(*) FROM {{QbPublicationCard}} c
     WHERE c.{{QbPublicationCard.wentOnAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND c.{{QbPublicationCard.wentOnAt}} < mo.month_end AT TIME ZONE 'UTC') AS publicationsWentOn,
    (SELECT count(*) FROM {{QbProfile}} p
     WHERE p.{{QbProfile.createdAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND p.{{QbProfile.createdAt}} < mo.month_end AT TIME ZONE 'UTC'
       AND NOT EXISTS (SELECT 1 FROM {{QbSponsor}} sp WHERE sp.{{QbSponsor.userId}} = p.{{QbProfile.userId}}
                       AND sp.{{QbSponsor.submittedAt}} <= p.{{QbProfile.createdAt}})) AS newTakers,
    (SELECT count(DISTINCT a.{{QbAttempt.userId}}) FROM {{QbAttempt}} a
     WHERE a.{{QbAttempt.startedAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND a.{{QbAttempt.startedAt}} < mo.month_end AT TIME ZONE 'UTC') AS activeTakers,
    (SELECT count(*) FROM {{QbAttempt}} a
     WHERE a.{{QbAttempt.startedAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND a.{{QbAttempt.startedAt}} < mo.month_end AT TIME ZONE 'UTC') AS attempts,
    (SELECT count(*) FROM {{QbAttempt}} a
     WHERE a.{{QbAttempt.submittedAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND a.{{QbAttempt.submittedAt}} < mo.month_end AT TIME ZONE 'UTC') AS completions,
    (SELECT count(*) FROM {{QbAttempt}} a
     WHERE a.{{QbAttempt.passed}} = true
       AND a.{{QbAttempt.submittedAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND a.{{QbAttempt.submittedAt}} < mo.month_end AT TIME ZONE 'UTC') AS passes,
    (SELECT count(*) FROM {{QbRedemption}} r
     WHERE r.{{QbRedemption.dueAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND r.{{QbRedemption.dueAt}} < mo.month_end AT TIME ZONE 'UTC')
    + (SELECT count(*) FROM {{QbDigitalPrize}} g
       WHERE g.{{QbDigitalPrize.dueAt}} >= mo.month_start AT TIME ZONE 'UTC'
         AND g.{{QbDigitalPrize.dueAt}} < mo.month_end AT TIME ZONE 'UTC') AS prizesWon,
    (SELECT count(*) FROM {{QbRedemption}} r
     WHERE r.{{QbRedemption.claimedAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND r.{{QbRedemption.claimedAt}} < mo.month_end AT TIME ZONE 'UTC') AS prizesClaimed,
    (SELECT count(*) FROM {{QbRedemption}} r
     WHERE r.{{QbRedemption.redeemedAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND r.{{QbRedemption.redeemedAt}} < mo.month_end AT TIME ZONE 'UTC')
    + (SELECT count(*) FROM {{QbDigitalPrize}} g
       WHERE g.{{QbDigitalPrize.deliveredAt}} >= mo.month_start AT TIME ZONE 'UTC'
         AND g.{{QbDigitalPrize.deliveredAt}} < mo.month_end AT TIME ZONE 'UTC') AS prizesRedeemed,
    (SELECT count(*) FROM {{QbInvitation}} i
     WHERE i.{{QbInvitation.lastSentAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND i.{{QbInvitation.lastSentAt}} < mo.month_end AT TIME ZONE 'UTC') AS invitationsSent,
    (SELECT count(*) FROM {{QbInvitation}} i
     WHERE i.{{QbInvitation.answeredAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND i.{{QbInvitation.answeredAt}} < mo.month_end AT TIME ZONE 'UTC') AS invitationsAnswered,
    (SELECT count(*) FROM {{QbTopUp}} u
     WHERE u.{{QbTopUp.status}} = 'PAID'
       AND u.{{QbTopUp.paidAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND u.{{QbTopUp.paidAt}} < mo.month_end AT TIME ZONE 'UTC') AS topUps,
    (SELECT coalesce(sum(u.{{QbTopUp.amount}}), 0) FROM {{QbTopUp}} u
     WHERE u.{{QbTopUp.status}} = 'PAID'
       AND u.{{QbTopUp.paidAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND u.{{QbTopUp.paidAt}} < mo.month_end AT TIME ZONE 'UTC') AS topUpAmount,
    (SELECT coalesce(sum(a.{{QbAttempt.reward}}), 0) FROM {{QbAttempt}} a
     WHERE a.{{QbAttempt.rewardStatus}} = 'PAID'
       AND a.{{QbAttempt.rewardPaidAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND a.{{QbAttempt.rewardPaidAt}} < mo.month_end AT TIME ZONE 'UTC') AS rewardsPaid,
    (SELECT coalesce(sum(a.{{QbAttempt.fee}}), 0) FROM {{QbAttempt}} a
     WHERE a.{{QbAttempt.rewardStatus}} = 'PAID'
       AND a.{{QbAttempt.rewardPaidAt}} >= mo.month_start AT TIME ZONE 'UTC'
       AND a.{{QbAttempt.rewardPaidAt}} < mo.month_end AT TIME ZONE 'UTC') AS commission
FROM months mo
