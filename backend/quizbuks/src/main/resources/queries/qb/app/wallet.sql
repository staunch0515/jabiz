/*---
id: qb.app.wallet
description: The taker's wallet (U-50) - what is available, earned in all, waiting for the sponsors, on its way out, and what is held back from transfers
entities: [LedgerEntry, LedgerAccount, LedgerTransaction, QbAttempt, QbPublicationCard, QbTopUp, SysParam]
datasets:
  LedgerEntry: urn:jabiz:dataset:taker:LedgerEntry
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbTopUp: urn:jabiz:dataset:default:QbTopUp
  SysParam: urn:jabiz:dataset:now:SysParam
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
-- them (credit less debit), earned in all its credits but the money that came back from transfers (a failed sending
-- reversed, a part Stripe reversed: not earned again), on the way out account 2130 (transfers, Q6b). Pending rewards
-- are not in the ledger (requirement 7.1): the rewards of the caller's attempts waiting for their sponsor, before the
-- commission (an estimate).
-- Held back from transfers (Q10b, reviews M3 and M-b) by the rule of qb.wallet.taker-transferable, which the transfers
-- use (wallet.RewardHolds): of the rewards posted within qb.transfer.hold-days before `at`, and of those of a sponsor
-- with an open dispute posted from the disputed payment on, what the transfers have not taken (a debit takes free money
-- first, then recent, then disputed, of what was credited before it), never more than the balance. heldOpen is the
-- part disputes hold (no date; the taker is not told which sponsor), heldUntil when the last reward held by its age
-- only is free. Without `at`, or without the parameter, held is null. The parameter is read at the server's clock
-- (the dataset without time travel, review L9), as the transfers read it.
-- Money that comes back from a transfer (its sending reversed after a failure, a part Stripe reversed; source QbTransfer,
-- the same sourceId as the sending) is the transfer's own, not free money (verification of review M-b): it is netted
-- against the transfer's debit at the debit's place, as if the transfer had sent only what stayed out
-- (RewardHolds.netted; a transfer has one 2120 debit).
WITH raw AS (
    SELECT e.{{LedgerEntry.direction}} AS l_direction, e.{{LedgerEntry.amount}} AS l_amount,
           e.{{LedgerEntry.dimension2}} AS publication, t.{{LedgerTransaction.bookingTime}} AS booked,
           t.{{LedgerTransaction.sourceEntity}} AS source, t.{{LedgerTransaction.processSeqId}} AS seq,
           t.{{LedgerTransaction.transactionId}} AS tx, t.{{LedgerTransaction.sourceId}} AS source_id,
           e.{{LedgerEntry.lineNo}} AS line_no
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2120'
      AND CAST(:at AS timestamptz) IS NOT NULL),
returned AS (
    SELECT r.source_id, sum(r.l_amount) AS back
    FROM raw r
    WHERE r.source = 'QbTransfer' AND r.l_direction = 'CREDIT'
    GROUP BY r.source_id),
sends AS (
    SELECT r.source_id, r.tx, r.line_no,
           row_number() OVER (PARTITION BY r.source_id ORDER BY r.booked, r.seq, r.tx, r.line_no) AS n
    FROM raw r
    WHERE r.source = 'QbTransfer' AND r.l_direction <> 'CREDIT'),
lines AS (
    SELECT r.l_direction, r.publication, r.booked, r.source, r.seq, r.tx, r.line_no,
           CASE WHEN s.n = 1 THEN greatest(r.l_amount - coalesce(b.back, 0), 0) ELSE r.l_amount END AS l_amount
    FROM raw r
    LEFT JOIN sends s ON s.tx = r.tx AND s.line_no = r.line_no
    LEFT JOIN returned b ON s.n = 1 AND b.source_id = r.source_id
    WHERE NOT (r.source = 'QbTransfer' AND r.l_direction = 'CREDIT'
               AND EXISTS (SELECT 1 FROM sends x WHERE x.source_id = r.source_id))),
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
kinds AS (
    SELECT l.l_amount, l.booked, l.seq, l.tx, l.line_no,
           CASE WHEN l.l_direction <> 'CREDIT' THEN 'DEBIT'
                WHEN l.source IN ('QbAttempt', 'QbReferral') AND d.since IS NOT NULL AND l.booked >= d.since THEN 'DISPUTED'
                WHEN l.source IN ('QbAttempt', 'QbReferral') AND l.booked > CAST(:at AS timestamptz) - make_interval(days => h.days)
                    THEN 'RECENT'
                ELSE 'FREE' END AS kind
    FROM lines l
    CROSS JOIN hold h
    LEFT JOIN {{QbPublicationCard}} c
        ON l.l_direction = 'CREDIT' AND l.source IN ('QbAttempt', 'QbReferral')
       AND c.{{QbPublicationCard.publicationId}} = CASE WHEN l.publication ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                                                        THEN CAST(l.publication AS uuid) END
    LEFT JOIN disputed d ON d.sponsor = c.{{QbPublicationCard.publisherId}}),
running AS (
    SELECT k.kind, k.l_amount, k.booked,
           sum(CASE WHEN k.kind = 'DEBIT' THEN k.l_amount ELSE 0 END) OVER w
               - sum(CASE WHEN k.kind = 'FREE' THEN k.l_amount ELSE 0 END) OVER w AS over_free,
           sum(CASE WHEN k.kind = 'DEBIT' THEN k.l_amount ELSE 0 END) OVER w
               - sum(CASE WHEN k.kind IN ('FREE', 'RECENT') THEN k.l_amount ELSE 0 END) OVER w AS over_recent
    FROM kinds k
    WINDOW w AS (ORDER BY k.booked, k.seq, CASE WHEN k.kind = 'DEBIT' THEN 1 ELSE 0 END, k.tx, k.line_no
                 ROWS UNBOUNDED PRECEDING)),
totals AS (
    SELECT coalesce(sum(CASE WHEN r.kind = 'DEBIT' THEN -r.l_amount ELSE r.l_amount END), 0) AS balance,
           coalesce(sum(r.l_amount) FILTER (WHERE r.kind IN ('RECENT', 'DISPUTED')), 0)
               - greatest(coalesce(max(r.over_free), 0), 0) AS held_left,
           coalesce(sum(r.l_amount) FILTER (WHERE r.kind = 'DISPUTED'), 0)
               - greatest(coalesce(max(r.over_recent), 0), 0) AS open_left,
           max(r.booked) FILTER (WHERE r.kind = 'RECENT') AS last_recent
    FROM running r),
bounded AS (
    SELECT t.last_recent, least(greatest(t.held_left, 0), greatest(t.balance, 0)) AS held, t.open_left
    FROM totals t),
holds AS (
    SELECT b.held, least(greatest(b.open_left, 0), b.held) AS held_open,
           CASE WHEN b.held > least(greatest(b.open_left, 0), b.held)
                THEN b.last_recent + make_interval(days => (SELECT h.days FROM hold h)) END AS held_until
    FROM bounded b)
SELECT
    coalesce(l.l_available, 0) AS available,
    coalesce(l.l_earned, 0)    AS totalEarned,
    coalesce(p.p_count, 0)     AS pendingCount,
    coalesce(p.p_amount, 0)    AS pendingAmount,
    coalesce(l.l_transfer, 0)  AS inTransfer,
    CASE WHEN CAST(:at AS timestamptz) IS NOT NULL AND EXISTS (SELECT 1 FROM hold) THEN h.held END AS held,
    CASE WHEN CAST(:at AS timestamptz) IS NOT NULL AND EXISTS (SELECT 1 FROM hold) THEN h.held_open END AS heldOpen,
    CASE WHEN CAST(:at AS timestamptz) IS NOT NULL AND EXISTS (SELECT 1 FROM hold) THEN h.held_until END AS heldUntil
FROM (
    SELECT
        sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '2120' THEN CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT'
            THEN e.{{LedgerEntry.amount}} ELSE -e.{{LedgerEntry.amount}} END END) AS l_available,
        sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '2120' AND e.{{LedgerEntry.direction}} = 'CREDIT'
                  AND t.{{LedgerTransaction.sourceEntity}} IS DISTINCT FROM 'QbTransfer'
            THEN e.{{LedgerEntry.amount}} END) AS l_earned,
        sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '2130' THEN CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT'
            THEN e.{{LedgerEntry.amount}} ELSE -e.{{LedgerEntry.amount}} END END) AS l_transfer
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE a.{{LedgerAccount.accountCode}} IN ('2120', '2130')) l
CROSS JOIN (
    SELECT count(*) AS p_count, sum(m.{{QbAttempt.reward}}) AS p_amount
    FROM {{QbAttempt}} m
    WHERE m.{{QbAttempt.rewardStatus}} = 'PENDING') p
CROSS JOIN holds h
