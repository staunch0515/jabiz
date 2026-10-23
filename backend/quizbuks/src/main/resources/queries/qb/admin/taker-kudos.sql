/*---
id: qb.admin.taker-kudos
description: Each taker's answering and Kudos at a glance (Q14) - attempts, completed ones, Kudos earned, rewards waiting for a sponsor's top-up, the balance, what is being transferred and what was transferred - for finance
entities: [QbAttempt, QbWallet, QbTransfer]
datasets:
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbTransfer: urn:jabiz:dataset:default:QbTransfer
results:
  userId:         { from: QbAttempt.userId }
  attempts:       { kind: { type: numeric, precision: 12, scale: 0 } }
  completed:      { kind: { type: numeric, precision: 12, scale: 0 } }
  earned:         { kind: { type: monetary, currency: JPY, scale: 0 } }
  pendingRewards: { kind: { type: monetary, currency: JPY, scale: 0 } }
  balance:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  inTransfer:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  transferred:    { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  filters: [userId]
  sorts:   [earned, balance, attempts]
  defaultSort: { field: earned, asc: false }
  key: [userId]
permissions: [qb.admin.finance.read]
---*/
-- The takers who answered at least once. Earned is the taker's share of the paid rewards (reward less the
-- commission, as posted to 2120); pending rewards wait for their sponsor's top-up and are not in the ledger. The
-- balance and what is on its way out are the wallet's cache (the ledger is the truth, reconciled daily: qb.admin.wallets
-- says the same); transferred is what Stripe received for them (the net of the transfers sent).
SELECT
    a.a_user                       AS userId,
    a.a_attempts                   AS attempts,
    a.a_completed                  AS completed,
    a.a_earned                     AS earned,
    a.a_pending                    AS pendingRewards,
    coalesce(w.{{QbWallet.balance}}, 0)    AS balance,
    coalesce(w.{{QbWallet.pendingOut}}, 0) AS inTransfer,
    coalesce(t.t_sent, 0)          AS transferred
FROM (
    SELECT m.{{QbAttempt.userId}} AS a_user,
           count(*) AS a_attempts,
           count(*) FILTER (WHERE m.{{QbAttempt.status}} IN ('SUBMITTED', 'EXPIRED')) AS a_completed,
           coalesce(sum(m.{{QbAttempt.reward}} - coalesce(m.{{QbAttempt.fee}}, 0))
               FILTER (WHERE m.{{QbAttempt.rewardStatus}} = 'PAID'), 0) AS a_earned,
           coalesce(sum(m.{{QbAttempt.reward}}) FILTER (WHERE m.{{QbAttempt.rewardStatus}} = 'PENDING'), 0) AS a_pending
    FROM {{QbAttempt}} m
    GROUP BY m.{{QbAttempt.userId}}) a
LEFT JOIN {{QbWallet}} w ON w.{{QbWallet.ownerId}} = a.a_user AND w.{{QbWallet.kind}} = 'TAKER'
LEFT JOIN (
    SELECT x.{{QbTransfer.userId}} AS t_user, sum(x.{{QbTransfer.net}}) AS t_sent
    FROM {{QbTransfer}} x
    WHERE x.{{QbTransfer.status}} = 'SENT'
    GROUP BY x.{{QbTransfer.userId}}) t ON t.t_user = a.a_user
