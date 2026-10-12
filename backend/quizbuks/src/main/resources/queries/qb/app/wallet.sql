/*---
id: qb.app.wallet
description: The taker's wallet (U-50) - what is available, earned in all, waiting for the sponsors and on its way out
entities: [LedgerEntry, LedgerAccount, QbAttempt]
datasets:
  LedgerEntry: urn:jabiz:dataset:taker:LedgerEntry
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
results:
  available:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  totalEarned:   { kind: { type: monetary, currency: JPY, scale: 0 } }
  pendingCount:  { kind: { type: numeric, precision: 9, scale: 0 } }
  pendingAmount: { kind: { type: monetary, currency: JPY, scale: 0 } }
  inTransfer:    { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.me]
---*/
-- The caller's own ledger lines (party = the caller, indexed) and attempts: one row. Available is account 2120 for
-- them (credit less debit), earned in all its credits, on the way out account 2130 (transfers, Q6b). Pending rewards
-- are not in the ledger (requirement 7.1): the rewards of the caller's attempts waiting for their sponsor, before the
-- commission (an estimate).
SELECT
    coalesce(l.l_available, 0) AS available,
    coalesce(l.l_earned, 0)    AS totalEarned,
    coalesce(p.p_count, 0)     AS pendingCount,
    coalesce(p.p_amount, 0)    AS pendingAmount,
    coalesce(l.l_transfer, 0)  AS inTransfer
FROM (
    SELECT
        sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '2120' THEN CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT'
            THEN e.{{LedgerEntry.amount}} ELSE -e.{{LedgerEntry.amount}} END END) AS l_available,
        sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '2120' AND e.{{LedgerEntry.direction}} = 'CREDIT'
            THEN e.{{LedgerEntry.amount}} END) AS l_earned,
        sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '2130' THEN CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT'
            THEN e.{{LedgerEntry.amount}} ELSE -e.{{LedgerEntry.amount}} END END) AS l_transfer
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} IN ('2120', '2130')) l
CROSS JOIN (
    SELECT count(*) AS p_count, sum(m.{{QbAttempt.reward}}) AS p_amount
    FROM {{QbAttempt}} m
    WHERE m.{{QbAttempt.rewardStatus}} = 'PENDING') p
