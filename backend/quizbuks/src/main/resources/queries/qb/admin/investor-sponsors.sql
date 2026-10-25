/*---
id: qb.admin.investor-sponsors
description: >-
  The merchants behind a month's figures (Q27): each merchant with attempts, prizes or invitations in the period,
  with their numbers; the most attempted first
entities: [QbSponsor, QbAttempt, QbRedemption, QbInvitation]
datasets:
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbRedemption: urn:jabiz:dataset:default:QbRedemption
  QbInvitation: urn:jabiz:dataset:default:QbInvitation
params:
  from: { like: QbAttempt.startedAt, required: true, description: "The period's first instant (UTC)" }
  to:   { like: QbAttempt.startedAt, required: true, description: "The instant after the period (UTC)" }
results:
  company:             { from: QbSponsor.company }
  takers:              { kind: { type: numeric, precision: 9, scale: 0 } }
  attempts:            { kind: { type: numeric, precision: 9, scale: 0 } }
  completions:         { kind: { type: numeric, precision: 9, scale: 0 } }
  passes:              { kind: { type: numeric, precision: 9, scale: 0 } }
  prizesWon:           { kind: { type: numeric, precision: 9, scale: 0 } }
  prizesRedeemed:      { kind: { type: numeric, precision: 9, scale: 0 } }
  invitationsSent:     { kind: { type: numeric, precision: 9, scale: 0 } }
  invitationsAnswered: { kind: { type: numeric, precision: 9, scale: 0 } }
  sponsorId:           { from: QbAttempt.sponsorId }
list:
  sorts: [attempts, company]
  defaultSort: { field: attempts, asc: false }
  key: [sponsorId]
permissions: [qb.admin.finance.read]
report:
  period: { from: from, to: to }
  landscape: true
---*/
-- By the same times as qb.admin.investor-metrics: attempts by their start, completions and passes by their
-- submission, prizes by their creation and collection, invitations by their last send and their answer. A merchant
-- appears when any of these fell in the period; the company name is the sponsor record's current one (the id when
-- there is none). The merchant's id is the last column, for the key only: the admin page does not show it.
WITH per AS (
    SELECT a.{{QbAttempt.sponsorId}} AS s,
           count(DISTINCT a.{{QbAttempt.userId}}) FILTER (WHERE a.{{QbAttempt.startedAt}} >= :from
               AND a.{{QbAttempt.startedAt}} < :to) AS takers,
           count(*) FILTER (WHERE a.{{QbAttempt.startedAt}} >= :from AND a.{{QbAttempt.startedAt}} < :to) AS attempts,
           count(*) FILTER (WHERE a.{{QbAttempt.submittedAt}} >= :from AND a.{{QbAttempt.submittedAt}} < :to)
               AS completions,
           count(*) FILTER (WHERE a.{{QbAttempt.passed}} = true AND a.{{QbAttempt.submittedAt}} >= :from
               AND a.{{QbAttempt.submittedAt}} < :to) AS passes
    FROM {{QbAttempt}} a
    GROUP BY a.{{QbAttempt.sponsorId}}
),
prizes AS (
    SELECT r.{{QbRedemption.sponsorId}} AS s,
           count(*) FILTER (WHERE r.{{QbRedemption.dueAt}} >= :from AND r.{{QbRedemption.dueAt}} < :to) AS won,
           count(*) FILTER (WHERE r.{{QbRedemption.redeemedAt}} >= :from AND r.{{QbRedemption.redeemedAt}} < :to)
               AS redeemed
    FROM {{QbRedemption}} r
    GROUP BY r.{{QbRedemption.sponsorId}}
),
inv AS (
    SELECT i.{{QbInvitation.sponsorId}} AS s,
           count(*) FILTER (WHERE i.{{QbInvitation.lastSentAt}} >= :from AND i.{{QbInvitation.lastSentAt}} < :to)
               AS sent,
           count(*) FILTER (WHERE i.{{QbInvitation.answeredAt}} >= :from AND i.{{QbInvitation.answeredAt}} < :to)
               AS answered
    FROM {{QbInvitation}} i
    GROUP BY i.{{QbInvitation.sponsorId}}
),
ids AS (
    SELECT s FROM per UNION SELECT s FROM prizes UNION SELECT s FROM inv
)
SELECT
    coalesce(sp.{{QbSponsor.company}}, CAST(ids.s AS varchar)) AS company,
    coalesce(per.takers, 0)         AS takers,
    coalesce(per.attempts, 0)       AS attempts,
    coalesce(per.completions, 0)    AS completions,
    coalesce(per.passes, 0)         AS passes,
    coalesce(prizes.won, 0)         AS prizesWon,
    coalesce(prizes.redeemed, 0)    AS prizesRedeemed,
    coalesce(inv.sent, 0)           AS invitationsSent,
    coalesce(inv.answered, 0)       AS invitationsAnswered,
    ids.s                           AS sponsorId
FROM ids
LEFT JOIN per ON per.s = ids.s
LEFT JOIN prizes ON prizes.s = ids.s
LEFT JOIN inv ON inv.s = ids.s
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = ids.s
WHERE coalesce(per.attempts, 0) + coalesce(per.completions, 0) + coalesce(prizes.won, 0) + coalesce(prizes.redeemed, 0)
    + coalesce(inv.sent, 0) + coalesce(inv.answered, 0) > 0
