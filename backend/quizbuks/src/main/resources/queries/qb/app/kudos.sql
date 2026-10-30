/*---
id: qb.app.kudos
description: The taker's Kudos by reward method (Q58) - earned, settled, lapsed and still owed for each of the four, in all or in a period
entities: [LedgerEntry, LedgerAccount, LedgerTransaction, QbAttempt, QbCashReward, QbRedemption, QbDigitalPrize]
datasets:
  LedgerEntry: urn:jabiz:dataset:taker:LedgerEntry
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
  QbCashReward: urn:jabiz:dataset:taker:QbCashReward
  QbRedemption: urn:jabiz:dataset:taker:QbRedemption
  QbDigitalPrize: urn:jabiz:dataset:taker:QbDigitalPrize
params:
  from: { kind: { type: temporal, role: EVENT_TIME }, description: Only what was earned or settled from this time on (all when absent) }
  to:   { kind: { type: temporal, role: EVENT_TIME }, description: Only what was earned or settled before this time (all when absent) }
results:
  method:        { kind: { type: text, maxLength: 20 } }
  earned:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  settled:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  lapsed:        { kind: { type: monetary, currency: JPY, scale: 0 } }
  balance:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  rewards:       { kind: { type: numeric, precision: 9, scale: 0 } }
  pendingCount:  { kind: { type: numeric, precision: 9, scale: 0 } }
  pendingAmount: { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.me]
---*/
-- One row per reward method (docs/quizbuks/plans/Q58-kudos-by-method.md), always all four. Earned, settled and lapsed
-- count what happened in the period (from and to, each optional: absent, all of time); the balance is what is still
-- owed now, whatever the period. Nothing is booked for this: each method's own records say it.
-- PLATFORM is the wallet (account 2120 of the caller): earned its credits less its debits that are not transfers (a
-- reward reversed is not earned), settled what transfers took (their debits less the money that came back from them),
-- the balance the account's. Rewards waiting for their sponsor are not in the wallet yet (pendingCount/Amount, the
-- reward before the commission). The wallet holds what the taker received: the reward less the commission.
-- SPONSOR_CASH, OFFLINE_REDEEM and DIGITAL_PRIZE are their records' amounts (the Kudos they are worth; no commission),
-- earned when owed (dueAt) unless voided; settled once the taker confirmed the cash (or an administrator resolved it
-- as paid), the prize was handed over, or the digital prize was delivered; lapsed an expired prize; the balance what is
-- still open.
WITH period AS (
    SELECT CAST(:from AS timestamptz) AS p_from, CAST(:to AS timestamptz) AS p_to
),
wallet AS (
    SELECT
        sum(CASE WHEN w.in_period AND NOT w.transfer THEN w.signed END) AS earned,
        sum(CASE WHEN w.in_period AND w.transfer THEN -w.signed END) AS settled,
        sum(w.signed) AS balance,
        count(CASE WHEN w.in_period AND NOT w.transfer AND w.signed > 0 THEN 1 END) AS rewards
    FROM (
        SELECT
            CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
                 ELSE -e.{{LedgerEntry.amount}} END AS signed,
            t.{{LedgerTransaction.sourceEntity}} IS NOT DISTINCT FROM 'QbTransfer' AS transfer,
            (p.p_from IS NULL OR t.{{LedgerTransaction.bookingTime}} >= p.p_from)
                AND (p.p_to IS NULL OR t.{{LedgerTransaction.bookingTime}} < p.p_to) AS in_period
        FROM {{LedgerEntry}} e
        JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
        JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
        CROSS JOIN period p
        WHERE a.{{LedgerAccount.accountCode}} = '2120') w
),
pending AS (
    SELECT count(*) AS p_count, sum(m.{{QbAttempt.reward}}) AS p_amount
    FROM {{QbAttempt}} m
    WHERE m.{{QbAttempt.rewardStatus}} = 'PENDING'
),
cash AS (
    SELECT
        sum(CASE WHEN r.{{QbCashReward.status}} <> 'VOID' AND (p.p_from IS NULL OR r.{{QbCashReward.dueAt}} >= p.p_from)
                  AND (p.p_to IS NULL OR r.{{QbCashReward.dueAt}} < p.p_to) THEN r.{{QbCashReward.amount}} END) AS earned,
        sum(CASE WHEN r.{{QbCashReward.status}} = 'CONFIRMED'
                  AND (p.p_from IS NULL OR coalesce(r.{{QbCashReward.confirmedAt}}, r.{{QbCashReward.resolvedAt}}) >= p.p_from)
                  AND (p.p_to IS NULL OR coalesce(r.{{QbCashReward.confirmedAt}}, r.{{QbCashReward.resolvedAt}}) < p.p_to)
            THEN r.{{QbCashReward.amount}} END) AS settled,
        sum(CASE WHEN r.{{QbCashReward.status}} IN ('DUE', 'MARKED_PAID', 'OVERDUE', 'DISPUTED')
            THEN r.{{QbCashReward.amount}} END) AS balance,
        count(CASE WHEN r.{{QbCashReward.status}} <> 'VOID' AND (p.p_from IS NULL OR r.{{QbCashReward.dueAt}} >= p.p_from)
                    AND (p.p_to IS NULL OR r.{{QbCashReward.dueAt}} < p.p_to) THEN 1 END) AS rewards
    FROM {{QbCashReward}} r
    CROSS JOIN period p
),
redeem AS (
    SELECT
        sum(CASE WHEN r.{{QbRedemption.status}} <> 'VOID' AND (p.p_from IS NULL OR r.{{QbRedemption.dueAt}} >= p.p_from)
                  AND (p.p_to IS NULL OR r.{{QbRedemption.dueAt}} < p.p_to) THEN r.{{QbRedemption.amount}} END) AS earned,
        sum(CASE WHEN r.{{QbRedemption.status}} = 'REDEEMED'
                  AND (p.p_from IS NULL OR r.{{QbRedemption.redeemedAt}} >= p.p_from)
                  AND (p.p_to IS NULL OR r.{{QbRedemption.redeemedAt}} < p.p_to) THEN r.{{QbRedemption.amount}} END) AS settled,
        sum(CASE WHEN r.{{QbRedemption.status}} = 'EXPIRED'
                  AND (p.p_from IS NULL OR r.{{QbRedemption.expiresAt}} >= p.p_from)
                  AND (p.p_to IS NULL OR r.{{QbRedemption.expiresAt}} < p.p_to) THEN r.{{QbRedemption.amount}} END) AS lapsed,
        sum(CASE WHEN r.{{QbRedemption.status}} IN ('DUE', 'CLAIMED') THEN r.{{QbRedemption.amount}} END) AS balance,
        count(CASE WHEN r.{{QbRedemption.status}} <> 'VOID' AND (p.p_from IS NULL OR r.{{QbRedemption.dueAt}} >= p.p_from)
                    AND (p.p_to IS NULL OR r.{{QbRedemption.dueAt}} < p.p_to) THEN 1 END) AS rewards
    FROM {{QbRedemption}} r
    CROSS JOIN period p
),
digital AS (
    SELECT
        sum(CASE WHEN d.{{QbDigitalPrize.status}} <> 'VOID' AND (p.p_from IS NULL OR d.{{QbDigitalPrize.dueAt}} >= p.p_from)
                  AND (p.p_to IS NULL OR d.{{QbDigitalPrize.dueAt}} < p.p_to) THEN d.{{QbDigitalPrize.amount}} END) AS earned,
        sum(CASE WHEN d.{{QbDigitalPrize.status}} IN ('DELIVERED', 'CONFIRMED')
                  AND (p.p_from IS NULL OR coalesce(d.{{QbDigitalPrize.deliveredAt}}, d.{{QbDigitalPrize.resolvedAt}}) >= p.p_from)
                  AND (p.p_to IS NULL OR coalesce(d.{{QbDigitalPrize.deliveredAt}}, d.{{QbDigitalPrize.resolvedAt}}) < p.p_to)
            THEN d.{{QbDigitalPrize.amount}} END) AS settled,
        sum(CASE WHEN d.{{QbDigitalPrize.status}} IN ('DUE', 'OVERDUE', 'DISPUTED')
            THEN d.{{QbDigitalPrize.amount}} END) AS balance,
        count(CASE WHEN d.{{QbDigitalPrize.status}} <> 'VOID' AND (p.p_from IS NULL OR d.{{QbDigitalPrize.dueAt}} >= p.p_from)
                    AND (p.p_to IS NULL OR d.{{QbDigitalPrize.dueAt}} < p.p_to) THEN 1 END) AS rewards
    FROM {{QbDigitalPrize}} d
    CROSS JOIN period p
)
SELECT 'PLATFORM' AS method, coalesce(w.earned, 0) AS earned, coalesce(w.settled, 0) AS settled, 0 AS lapsed,
       coalesce(w.balance, 0) AS balance, w.rewards AS rewards, coalesce(pe.p_count, 0) AS pendingCount,
       coalesce(pe.p_amount, 0) AS pendingAmount
FROM wallet w CROSS JOIN pending pe
UNION ALL
SELECT 'SPONSOR_CASH', coalesce(c.earned, 0), coalesce(c.settled, 0), 0, coalesce(c.balance, 0), c.rewards, 0, 0
FROM cash c
UNION ALL
SELECT 'OFFLINE_REDEEM', coalesce(r.earned, 0), coalesce(r.settled, 0), coalesce(r.lapsed, 0), coalesce(r.balance, 0),
       r.rewards, 0, 0
FROM redeem r
UNION ALL
SELECT 'DIGITAL_PRIZE', coalesce(d.earned, 0), coalesce(d.settled, 0), 0, coalesce(d.balance, 0), d.rewards, 0, 0
FROM digital d
