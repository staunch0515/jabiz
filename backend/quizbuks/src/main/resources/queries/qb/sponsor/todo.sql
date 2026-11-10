/*---
id: qb.sponsor.todo
description: >-
  F-F3b: what the sponsor has to do, as counts for the home page's "to do" list and the prizes badge - claims to
  confirm, digital prizes to send or disputed, publications short of budget, rewards waiting for a top-up, overdue
  platform fee invoices, items in review (publications, the public page, shared videos), the uses of the sponsor's
  shared videos waiting for the sponsor as provider, and platform fee Kudos about to expire
entities: [QbRedemption, QbDigitalPrize, QbPublicationCard, QbPublication, QbWallet, QbFeeInvoice, QbSponsorPage, QbSharedVideo, QbVideoUse, QbFeeCreditGrant, SysParam]
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
  creditsExpiring:      { kind: { type: numeric, precision: 9, scale: 0 } }
permissions: [qb.publication.write]
---*/
-- One row of counts, every dataset the caller's own (the sponsor datasets hold their rows; the provider's uses are
-- those their users sent). No number is written here: the states are the ones the pages list, and the notice of
-- the fee credit is the parameter the reminder mail reads (qb.fee-credit.expiry-notice-days; none or malformed: none
-- counted). Claims are the sponsor's own prizes (a benefit's are handed over by its provider, as in
-- qb.sponsor.redemptions); the digital prizes are those the sponsor owes (to send, late) or that the taker disputes.
-- Rewards waiting are the wallet's pending count, as the wallet page shows. A page or a shared video counts while its
-- review is open.
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
    (SELECT count(*) FROM {{QbFeeCreditGrant}} g
      WHERE g.{{QbFeeCreditGrant.remaining}} > 0
        AND g.{{QbFeeCreditGrant.expiresAt}} > CAST(:at AS timestamptz)
        AND g.{{QbFeeCreditGrant.expiresAt}} <= CAST(:at AS timestamptz) + make_interval(days => (
            SELECT CAST(n.{{SysParam.value}} AS int) FROM {{SysParam}} n
             WHERE n.{{SysParam.paramKey}} = 'qb.fee-credit.expiry-notice-days'
               AND n.{{SysParam.value}} ~ '^[0-9]{1,9}$'))) AS creditsExpiring
