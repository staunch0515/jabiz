/*---
id: qb.wallet.taker-transferable
description: >-
  A taker's balance on account 2120 and the part of it held back from transfers (Q10b, review M3): the rewards
  posted within the hold period, and those from a sponsor with an open dispute, posted after the disputed top-up was
  paid
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
-- open while its top-up's disputeStatus is OPEN (TopUpClawback): rewards of that sponsor posted from the disputed
-- payment on are held until it closes, whatever their age. held counts each reward once; heldOpen is the part held
-- by a dispute (no date), heldUntil when the last reward held only by its age is free. The same rule is written in
-- qb.app.wallet for the taker's page.
WITH lines AS (
    SELECT e.{{LedgerEntry.direction}} AS l_direction, e.{{LedgerEntry.amount}} AS l_amount,
           e.{{LedgerEntry.dimension2}} AS publication, t.{{LedgerTransaction.bookingTime}} AS booked,
           t.{{LedgerTransaction.sourceEntity}} AS source
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE e.{{LedgerEntry.dimension1}} = CAST(:userId AS text)
      AND a.{{LedgerAccount.accountCode}} = '2120'),
disputed AS (
    SELECT u.{{QbTopUp.sponsorId}} AS sponsor, min(u.{{QbTopUp.paidAt}}) AS since
    FROM {{QbTopUp}} u
    WHERE u.{{QbTopUp.disputeStatus}} = 'OPEN'
    GROUP BY u.{{QbTopUp.sponsorId}}),
rewards AS (
    SELECT l.l_amount, l.booked,
           l.booked > CAST(:at AS timestamptz) - make_interval(days => CAST(:holdDays AS int)) AS recent,
           d.since IS NOT NULL AND l.booked >= d.since AS frozen
    FROM lines l
    LEFT JOIN {{QbPublicationCard}} c
        ON c.{{QbPublicationCard.publicationId}} = CASE WHEN l.publication ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                                                        THEN CAST(l.publication AS uuid) END
    LEFT JOIN disputed d ON d.sponsor = c.{{QbPublicationCard.sponsorId}}
    WHERE l.l_direction = 'CREDIT' AND l.source = 'QbAttempt')
SELECT
    (SELECT coalesce(sum(CASE WHEN l.l_direction = 'CREDIT' THEN l.l_amount ELSE -l.l_amount END), 0) FROM lines l)
        AS available,
    (SELECT coalesce(sum(r.l_amount), 0) FROM rewards r WHERE r.recent OR r.frozen) AS held,
    (SELECT coalesce(sum(r.l_amount), 0) FROM rewards r WHERE r.frozen) AS heldOpen,
    (SELECT max(r.booked) + make_interval(days => CAST(:holdDays AS int)) FROM rewards r
     WHERE r.recent AND NOT r.frozen) AS heldUntil
