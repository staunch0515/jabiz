/*---
id: qb.admin.finance-summary
description: >-
  What finance has to look at today (Q9c): the wallets' totals and the transfers waiting for review, in doubt or held;
  one row (the reconciliation and the money tasks are qb.admin.recon-summary's, qb.reconcile)
entities: [QbWallet, QbTransfer]
datasets:
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbTransfer: urn:jabiz:dataset:default:QbTransfer
params:
  staleBefore: { like: QbTransfer.createdAt, description: "A transfer still sending that was created before this time is in doubt (the page: an hour ago)" }
results:
  sponsorBalance:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  sponsorHeld:          { kind: { type: monetary, currency: JPY, scale: 0 } }
  sponsorPendingCount:  { kind: { type: numeric, precision: 12, scale: 0 } }
  sponsorPendingAmount: { kind: { type: monetary, currency: JPY, scale: 0 } }
  takerBalance:         { kind: { type: monetary, currency: JPY, scale: 0 } }
  takerPendingOut:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  transfersInReview:    { kind: { type: numeric, precision: 12, scale: 0 } }
  transfersInDoubt:     { kind: { type: numeric, precision: 12, scale: 0 } }
  heldTakers:           { kind: { type: numeric, precision: 12, scale: 0 } }
permissions: [qb.admin.finance.read]
---*/
-- One statement, one snapshot. The wallets are the caches (the ledger is the truth, reconciled daily). In doubt: CHECK,
-- or SENDING since before staleBefore (the job asks Stripe about those every hour, qb.system.transfers-stale). Held: a
-- taker whose rejected or reversed transfer nobody released yet (QB_TRANSFER_HOLD_RELEASE).
SELECT
    w.sponsor_balance        AS sponsorBalance,
    w.sponsor_frozen         AS sponsorHeld,
    w.sponsor_pending_count  AS sponsorPendingCount,
    w.sponsor_pending_amount AS sponsorPendingAmount,
    w.taker_balance          AS takerBalance,
    w.taker_out              AS takerPendingOut,
    x.in_review              AS transfersInReview,
    x.in_doubt               AS transfersInDoubt,
    x.held                   AS heldTakers
FROM (
    SELECT coalesce(sum(v.{{QbWallet.balance}}) FILTER (WHERE v.{{QbWallet.kind}} = 'SPONSOR'), 0) AS sponsor_balance,
           coalesce(sum(v.{{QbWallet.held}}) FILTER (WHERE v.{{QbWallet.kind}} = 'SPONSOR'), 0) AS sponsor_frozen,
           coalesce(sum(v.{{QbWallet.pendingCount}}) FILTER (WHERE v.{{QbWallet.kind}} = 'SPONSOR'), 0)
               AS sponsor_pending_count,
           coalesce(sum(v.{{QbWallet.pendingAmount}}) FILTER (WHERE v.{{QbWallet.kind}} = 'SPONSOR'), 0)
               AS sponsor_pending_amount,
           coalesce(sum(v.{{QbWallet.balance}}) FILTER (WHERE v.{{QbWallet.kind}} = 'TAKER'), 0) AS taker_balance,
           coalesce(sum(v.{{QbWallet.pendingOut}}) FILTER (WHERE v.{{QbWallet.kind}} = 'TAKER'), 0) AS taker_out
    FROM {{QbWallet}} v) w
CROSS JOIN (
    SELECT count(*) FILTER (WHERE t.{{QbTransfer.status}} = 'REVIEW') AS in_review,
           count(*) FILTER (WHERE t.{{QbTransfer.status}} = 'CHECK'
               OR (t.{{QbTransfer.status}} = 'SENDING' AND t.{{QbTransfer.createdAt}} < CAST(:staleBefore AS timestamptz)))
               AS in_doubt,
           count(DISTINCT t.{{QbTransfer.userId}}) FILTER (WHERE t.{{QbTransfer.releasedAt}} IS NULL
               AND (t.{{QbTransfer.status}} = 'REJECTED' OR t.{{QbTransfer.reversedAmount}} > 0)) AS held
    FROM {{QbTransfer}} t) x
