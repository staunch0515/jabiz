/*---
id: qb.wallet.taker-transferable
description: >-
  A taker's balance on account 2120 and the part of it held back from transfers (Q10b, reviews M3 and M-b): of the
  rewards posted within the hold period, and of those from a sponsor with an open dispute posted after the disputed
  top-up was paid, what is still in the balance
entities: [LedgerEntry, LedgerAccount, LedgerTransaction, QbPublicationCard, QbTopUp]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbTopUp: urn:jabiz:dataset:default:QbTopUp
params:
  userId:   { kind: { type: text, maxLength: 128 }, required: true, description: The taker }
  at:       { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time of the decision }
  holdDays: { kind: { type: numeric, precision: 3, scale: 0 }, required: true, description: The hold period in days (qb.transfer.hold-days) }
results:
  available: { kind: { type: monetary, currency: JPY, scale: 0 } }
  held:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  heldOpen:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  heldUntil: { kind: { type: temporal, role: EVENT_TIME } }
permissions: [qb.payout.system]
---*/
-- One statement, under the taker's wallet lock: what is committed and what this transaction posted. The taker's lines
-- only (the party, dimension 1, is indexed). A reward is a 2120 credit of a posting whose source is an attempt
-- (QbWallets.rewardPosting); its publication is dimension 2, and the publication's card names the sponsor. A dispute is
-- open while its top-up's disputeStatus is OPEN (TopUpClawback).
-- The model is wallet.RewardHolds (docs/quizbuks/02-design.md, "奖励的保留"; review M-b): each line is DISPUTED (a reward
-- of a disputed sponsor posted from the disputed payment on), RECENT (a reward within the hold period), FREE (any other
-- credit) or DEBIT. A debit takes free money first, then recent, then disputed, of what was credited before it (in booking
-- order, then the order the processes ran in), so a reward that left with an earlier transfer holds nothing back, and another sponsor's
-- reward is held only when the balance cannot keep both. The running sums give what the debits took from the held
-- (c_held) and from the disputed (c_open); held is never below 0 nor above the balance. heldOpen is the part held by
-- disputes (no date), heldUntil when the last reward held only by its age is free (none when nothing is so held).
-- The same rule is written in qb.app.wallet for the taker's page.
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
    WHERE e.{{LedgerEntry.dimension1}} = CAST(:userId AS text)
      AND a.{{LedgerAccount.accountCode}} = '2120'),
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
disputed AS (
    SELECT u.{{QbTopUp.sponsorId}} AS sponsor, min(u.{{QbTopUp.paidAt}}) AS since
    FROM {{QbTopUp}} u
    WHERE u.{{QbTopUp.disputeStatus}} = 'OPEN'
    GROUP BY u.{{QbTopUp.sponsorId}}),
kinds AS (
    SELECT l.l_amount, l.booked, l.seq, l.tx, l.line_no,
           CASE WHEN l.l_direction <> 'CREDIT' THEN 'DEBIT'
                WHEN l.source = 'QbAttempt' AND d.since IS NOT NULL AND l.booked >= d.since THEN 'DISPUTED'
                WHEN l.source = 'QbAttempt'
                     AND l.booked > CAST(:at AS timestamptz) - make_interval(days => CAST(:holdDays AS int))
                    THEN 'RECENT'
                ELSE 'FREE' END AS kind
    FROM lines l
    LEFT JOIN {{QbPublicationCard}} c
        ON l.l_direction = 'CREDIT' AND l.source = 'QbAttempt'
       AND c.{{QbPublicationCard.publicationId}} = CASE WHEN l.publication ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                                                        THEN CAST(l.publication AS uuid) END
    LEFT JOIN disputed d ON d.sponsor = c.{{QbPublicationCard.sponsorId}}),
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
    SELECT t.balance, t.last_recent,
           least(greatest(t.held_left, 0), greatest(t.balance, 0)) AS held, t.open_left
    FROM totals t)
SELECT
    b.balance AS available,
    b.held AS held,
    least(greatest(b.open_left, 0), b.held) AS heldOpen,
    CASE WHEN b.held > least(greatest(b.open_left, 0), b.held)
         THEN b.last_recent + make_interval(days => CAST(:holdDays AS int)) END AS heldUntil
FROM bounded b
