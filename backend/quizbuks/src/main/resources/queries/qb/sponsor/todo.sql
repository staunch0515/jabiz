/*---
id: qb.sponsor.todo
description: >-
  F-F3b: what the sponsor has to do, as counts for the home page's "to do" list and the prizes badge - claims to
  confirm, digital prizes to send or disputed, publications short of budget, rewards waiting for a top-up, overdue
  platform fee invoices, open ones the fee account cannot cover, items in review (publications, the public page, shared videos), the uses of the sponsor's
  shared videos waiting for the sponsor as provider, and platform fee Kudos about to expire
entities: [QbRedemption, QbDigitalPrize, QbPublicationCard, QbPublication, QbWallet, QbFeeInvoice, QbSponsorPage, QbSharedVideo, QbVideoUse, QbFeeCreditGrant, QbExposure, SysParam]
datasets:
  QbRedemption: urn:jabiz:dataset:sponsor:QbRedemption
  QbDigitalPrize: urn:jabiz:dataset:sponsor:QbDigitalPrize
  QbPublicationCard: urn:jabiz:dataset:sponsor:QbPublicationCard
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbWallet: urn:jabiz:dataset:sponsor:QbWallet
  QbFeeInvoice: urn:jabiz:dataset:sponsor:QbFeeInvoice
  QbSponsorPage: urn:jabiz:dataset:me:QbSponsorPage
  QbSharedVideo: urn:jabiz:dataset:me:QbSharedVideo
  QbVideoUse: urn:jabiz:dataset:provider:QbVideoUse
  QbFeeCreditGrant: urn:jabiz:dataset:sponsor:QbFeeCreditGrant
  QbExposure: urn:jabiz:dataset:default:QbExposure
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The client's now: the lots of fee credit that expire within the notice from it are counted" }
results:
  claimsToConfirm:      { kind: { type: numeric, precision: 9, scale: 0 } }
  digitalToSend:        { kind: { type: numeric, precision: 9, scale: 0 } }
  fundsShort:           { kind: { type: numeric, precision: 9, scale: 0 } }
  rewardsWaiting:       { kind: { type: numeric, precision: 9, scale: 0 } }
  invoicesOverdue:      { kind: { type: numeric, precision: 9, scale: 0 } }
  publicationsInReview: { kind: { type: numeric, precision: 9, scale: 0 } }
  pageInReview:         { kind: { type: numeric, precision: 9, scale: 0 } }
  sharedVideosInReview: { kind: { type: numeric, precision: 9, scale: 0 } }
  videoUsesToConfirm:   { kind: { type: numeric, precision: 9, scale: 0 } }
  feeInvoicesUncovered: { kind: { type: numeric, precision: 9, scale: 0 } }
  creditsExpiring:      { kind: { type: numeric, precision: 9, scale: 0 } }
  exposureLow:          { kind: { type: numeric, precision: 9, scale: 0 } }
permissions: [qb.publication.write]
---*/
-- One row of counts, every dataset the caller's own (the sponsor datasets hold their rows; the provider's uses are
-- those their users sent). No number is written here: the states are the ones the pages list, and the notice of
-- the fee credit is the parameter the reminder mail reads (qb.fee-credit.expiry-notice-days; none or malformed: none
-- counted). Claims are the sponsor's own prizes (a benefit's are handed over by its provider, as in
-- qb.sponsor.redemptions); the digital prizes are those the sponsor owes (to send, late) or that the taker disputes.
-- Rewards waiting are the wallet's pending count, as the wallet page shows. A page or a shared video counts while its
-- review is open.
-- feeInvoicesUncovered: the open invoices (OPEN, PARTIAL, OVERDUE, as qb.sponsor.wallet's unpaid ones) whose owed
-- amount (total less paid) is more than the fee account holds, as the wallet page works it out (feeAccountAvailable):
-- the fee credit's balance plus the cash account's balance less what is held.
-- exposureLow (F-M8): the sponsor's running publications that bought exposures and have at most the share
-- qb.exposure.low-ratio (never above a half; none or malformed: none counted) of them left, as the reminder mail reads it.
WITH account AS (
    SELECT coalesce(sum(greatest(0, w.{{QbWallet.balance}})) FILTER (WHERE w.{{QbWallet.kind}} = 'FEE_CREDIT'), 0)
         + coalesce(sum(greatest(0, w.{{QbWallet.balance}} - w.{{QbWallet.held}}))
                    FILTER (WHERE w.{{QbWallet.kind}} = 'CASH'), 0) AS available
    FROM {{QbWallet}} w)
SELECT
    (SELECT count(*) FROM {{QbRedemption}} r
      WHERE r.{{QbRedemption.status}} = 'CLAIMED' AND r.{{QbRedemption.fulfillerId}} IS NULL) AS claimsToConfirm,
    (SELECT count(*) FROM {{QbDigitalPrize}} g
      WHERE g.{{QbDigitalPrize.status}} IN ('DUE', 'OVERDUE', 'DISPUTED')) AS digitalToSend,
    (SELECT count(*) FROM {{QbPublicationCard}} c
      WHERE c.{{QbPublicationCard.status}} = 'FUNDS_SHORT') AS fundsShort,
    (SELECT coalesce(sum(w.{{QbWallet.pendingCount}}), 0) FROM {{QbWallet}} w
      WHERE w.{{QbWallet.kind}} = 'SPONSOR') AS rewardsWaiting,
    (SELECT count(*) FROM {{QbFeeInvoice}} i
      WHERE i.{{QbFeeInvoice.status}} = 'OVERDUE') AS invoicesOverdue,
    (SELECT count(*) FROM {{QbPublication}} p
      WHERE p.{{QbPublication.status}} = 'PENDING_REVIEW') AS publicationsInReview,
    (SELECT count(*) FROM {{QbSponsorPage}} s
      WHERE s.{{QbSponsorPage.status}} = 'PENDING') AS pageInReview,
    (SELECT count(*) FROM {{QbSharedVideo}} v
      WHERE v.{{QbSharedVideo.status}} = 'PENDING') AS sharedVideosInReview,
    (SELECT count(*) FROM {{QbVideoUse}} u
      WHERE u.{{QbVideoUse.status}} = 'SUBMITTED') AS videoUsesToConfirm,
    (SELECT count(*) FROM {{QbFeeInvoice}} i CROSS JOIN account a
      WHERE i.{{QbFeeInvoice.status}} IN ('OPEN', 'PARTIAL', 'OVERDUE')
        AND i.{{QbFeeInvoice.total}} - i.{{QbFeeInvoice.creditApplied}} - i.{{QbFeeInvoice.paidAmount}} > a.available) AS feeInvoicesUncovered,
    (SELECT count(*) FROM {{QbFeeCreditGrant}} g
      WHERE g.{{QbFeeCreditGrant.remaining}} > 0
        AND g.{{QbFeeCreditGrant.expiresAt}} > CAST(:at AS timestamptz)
        AND g.{{QbFeeCreditGrant.expiresAt}} <= CAST(:at AS timestamptz) + make_interval(days => (
            SELECT CAST(n.{{SysParam.value}} AS int) FROM {{SysParam}} n
             WHERE n.{{SysParam.paramKey}} = 'qb.fee-credit.expiry-notice-days'
               AND n.{{SysParam.value}} ~ '^[0-9]{1,9}$'))) AS creditsExpiring,
    (SELECT count(*) FROM {{QbPublicationCard}} c
      CROSS JOIN LATERAL (SELECT count(*) AS n FROM {{QbExposure}} e
                          WHERE e.{{QbExposure.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)
                            AND e.{{QbExposure.countedAt}} IS NOT NULL) x
      CROSS JOIN LATERAL (SELECT least(CAST(r.{{SysParam.value}} AS numeric), 0.5) AS ratio FROM {{SysParam}} r
                          WHERE r.{{SysParam.paramKey}} = 'qb.exposure.low-ratio'
                            AND r.{{SysParam.value}} ~ '^[0-9]+(\.[0-9]+)?$') q
      WHERE c.{{QbPublicationCard.exposurePurchased}} IS NOT NULL
        AND c.{{QbPublicationCard.status}} IN ('SCHEDULED', 'LIVE', 'PAUSED', 'FUNDS_SHORT')
        AND q.ratio > 0
        AND x.n < c.{{QbPublicationCard.exposurePurchased}}
        AND c.{{QbPublicationCard.exposurePurchased}} - x.n <= c.{{QbPublicationCard.exposurePurchased}} * q.ratio) AS exposureLow
