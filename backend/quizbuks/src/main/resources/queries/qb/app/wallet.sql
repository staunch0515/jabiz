/*---
id: qb.app.wallet
description: The taker's wallet (U-50) - what is available, earned in all, waiting for the sponsors, on its way out, and what is held back from transfers
entities: [LedgerEntry, LedgerAccount, LedgerTransaction, QbAttempt, QbPublicationCard, QbTopUp, SysParam]
datasets:
  LedgerEntry: urn:jabiz:dataset:taker:LedgerEntry
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbTopUp: urn:jabiz:dataset:default:QbTopUp
  SysParam: urn:jabiz:dataset:platform:SysParam
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, description: Now; without it nothing is said about what is held back }
results:
  available:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  totalEarned:   { kind: { type: monetary, currency: JPY, scale: 0 } }
  pendingCount:  { kind: { type: numeric, precision: 9, scale: 0 } }
  pendingAmount: { kind: { type: monetary, currency: JPY, scale: 0 } }
  inTransfer:    { kind: { type: monetary, currency: JPY, scale: 0 } }
  held:          { kind: { type: monetary, currency: JPY, scale: 0 } }
  heldOpen:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  heldUntil:     { kind: { type: temporal, role: EVENT_TIME } }
permissions: [qb.me]
---*/
-- The caller's own ledger lines (party = the caller, indexed) and attempts: one row. Available is account 2120 for
-- them (credit less debit), earned in all its credits, on the way out account 2130 (transfers, Q6b). Pending rewards
-- are not in the ledger (requirement 7.1): the rewards of the caller's attempts waiting for their sponsor, before the
-- commission (an estimate).
-- Held back from transfers (Q10b, review M3) by the rule of qb.wallet.taker-transferable, which the transfers use:
-- rewards posted within qb.transfer.hold-days before `at`, and those of a sponsor with an open dispute posted from the
-- disputed payment on. heldOpen is the part a dispute holds (no date; the taker is not told which sponsor),
-- heldUntil when the last reward held by its age only is free. Without `at`, or without the parameter, held is null.
WITH lines AS (
    SELECT e.{{LedgerEntry.direction}} AS l_direction, e.{{LedgerEntry.amount}} AS l_amount,
           e.{{LedgerEntry.dimension2}} AS publication, t.{{LedgerTransaction.bookingTime}} AS booked,
           t.{{LedgerTransaction.sourceEntity}} AS source
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2120'
      AND CAST(:at AS timestamptz) IS NOT NULL),
hold AS (
    SELECT CAST(max(p.{{SysParam.value}}) AS int) AS days
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.transfer.hold-days' AND p.{{SysParam.value}} ~ '^[0-9]{1,3}$'
    HAVING count(*) > 0),
disputed AS (
    SELECT u.{{QbTopUp.sponsorId}} AS sponsor, min(u.{{QbTopUp.paidAt}}) AS since
    FROM {{QbTopUp}} u
    WHERE u.{{QbTopUp.disputeStatus}} = 'OPEN'
    GROUP BY u.{{QbTopUp.sponsorId}}),
rewards AS (
    SELECT l.l_amount, l.booked, h.days,
           l.booked > CAST(:at AS timestamptz) - make_interval(days => h.days) AS recent,
           d.since IS NOT NULL AND l.booked >= d.since AS frozen
    FROM lines l
    CROSS JOIN hold h
    LEFT JOIN {{QbPublicationCard}} c
        ON c.{{QbPublicationCard.publicationId}} = CASE WHEN l.publication ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                                                        THEN CAST(l.publication AS uuid) END
    LEFT JOIN disputed d ON d.sponsor = c.{{QbPublicationCard.sponsorId}}
    WHERE l.l_direction = 'CREDIT' AND l.source = 'QbAttempt')
SELECT
    coalesce(l.l_available, 0) AS available,
    coalesce(l.l_earned, 0)    AS totalEarned,
    coalesce(p.p_count, 0)     AS pendingCount,
    coalesce(p.p_amount, 0)    AS pendingAmount,
    coalesce(l.l_transfer, 0)  AS inTransfer,
    CASE WHEN CAST(:at AS timestamptz) IS NOT NULL AND EXISTS (SELECT 1 FROM hold)
         THEN (SELECT coalesce(sum(r.l_amount), 0) FROM rewards r WHERE r.recent OR r.frozen) END AS held,
    CASE WHEN CAST(:at AS timestamptz) IS NOT NULL AND EXISTS (SELECT 1 FROM hold)
         THEN (SELECT coalesce(sum(r.l_amount), 0) FROM rewards r WHERE r.frozen) END AS heldOpen,
    (SELECT max(r.booked + make_interval(days => r.days)) FROM rewards r WHERE r.recent AND NOT r.frozen)
        AS heldUntil
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
