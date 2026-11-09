/*---
id: qb.sponsor.video-uses-provided
description: >-
  F-S14: the provider's side of shared videos' uses: the publications using the Sponsor's shared videos, each with the
  ledger's use once its user submitted it, the figures counted every day (final once it ended; each below the minimum
  group not shown) and the reference amount by them
entities: [QbVideoUseFigures, QbVideoUse, QbPublicationCard, QbSharedVideo, QbSponsor, SysParam]
datasets:
  QbVideoUseFigures: urn:jabiz:dataset:provider:QbVideoUseFigures
  QbVideoUse: urn:jabiz:dataset:provider:QbVideoUse
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSharedVideo: urn:jabiz:dataset:default:QbSharedVideo
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  publicationId: { kind: { type: text, maxLength: 36 }, description: "Only this publication; all when not given" }
results:
  publicationId:     { kind: { type: text, maxLength: 36 } }
  name:              { from: QbPublicationCard.name }
  publicationStatus: { from: QbPublicationCard.status }
  wentOnAt:          { from: QbPublicationCard.wentOnAt }
  endedAt:           { from: QbPublicationCard.endedAt }
  sharedVideoId:     { kind: { type: text, maxLength: 36 } }
  videoTitle:        { kind: { type: text } }
  userCompany:       { from: QbSponsor.company }
  videoUseId:        { from: QbVideoUse.videoUseId }
  status:            { from: QbVideoUse.status }
  periodFrom:        { from: QbVideoUse.periodFrom }
  periodTo:          { from: QbVideoUse.periodTo }
  pricing:           { from: QbVideoUse.pricing }
  price:             { from: QbVideoUse.price }
  note:              { from: QbVideoUse.note }
  submittedAt:       { from: QbVideoUse.submittedAt }
  confirmedAt:       { from: QbVideoUse.confirmedAt }
  confirmedRevision: { from: QbVideoUse.confirmedRevision }
  rejectedAt:        { from: QbVideoUse.rejectedAt }
  rejectReason:      { from: QbVideoUse.rejectReason }
  revision:          { from: QbVideoUse.revision }
  editedAt:          { from: QbVideoUse.editedAt }
  opens:             { kind: { type: numeric, precision: 12, scale: 0 } }
  successes:         { kind: { type: numeric, precision: 12, scale: 0 } }
  goes:              { kind: { type: numeric, precision: 12, scale: 0 } }
  qualified:         { kind: { type: numeric, precision: 12, scale: 0 } }
  minGroup:          { kind: { type: numeric, precision: 9, scale: 0 } }
  refreshedAt:       { from: QbVideoUseFigures.refreshedAt }
  finalisedAt:       { from: QbVideoUseFigures.finalisedAt }
  amount:            { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  filters: [status]
  sorts:   [wentOnAt, submittedAt]
  defaultSort: { field: wentOnAt, asc: false }
  key: [publicationId]
permissions: [qb.stats.read]
---*/
-- The caller's side only (both provider datasets hold the rows whose provider is the caller): the figures the daily
-- job counted for the publications using the caller's videos, and the uses their users sent at least once (the
-- dataset's scope: sentToProvider; a draft never sent is the user's own). The user is shown by their company. Each figure below k is not shown, and
-- the reference amount is VideoUseRules.amount, as on the user's side (qb.sponsor.video-uses).
WITH params AS (
    SELECT greatest(3, coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,9}$'
                                         THEN CAST(p.{{SysParam.value}} AS int) END), 5)) AS k
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.stats.min-group'),
rows AS (
    SELECT coalesce(f.{{QbVideoUseFigures.publicationId}}, u.{{QbVideoUse.publicationId}}) AS pid,
           coalesce(f.{{QbVideoUseFigures.sharedVideoId}}, u.{{QbVideoUse.sharedVideoId}}) AS svid,
           coalesce(f.{{QbVideoUseFigures.sponsorId}}, u.{{QbVideoUse.sponsorId}}) AS uid,
           f.{{QbVideoUseFigures.opens}} AS f_opens, f.{{QbVideoUseFigures.successes}} AS f_successes,
           f.{{QbVideoUseFigures.goes}} AS f_goes, f.{{QbVideoUseFigures.qualified}} AS f_qualified,
           f.{{QbVideoUseFigures.refreshedAt}} AS f_refreshed, f.{{QbVideoUseFigures.finalisedAt}} AS f_finalised,
           u.{{QbVideoUse.videoUseId}} AS u_id, u.{{QbVideoUse.status}} AS u_status,
           u.{{QbVideoUse.periodFrom}} AS u_from, u.{{QbVideoUse.periodTo}} AS u_to,
           u.{{QbVideoUse.pricing}} AS u_pricing, u.{{QbVideoUse.price}} AS u_price, u.{{QbVideoUse.note}} AS u_note,
           u.{{QbVideoUse.submittedAt}} AS u_submitted, u.{{QbVideoUse.confirmedAt}} AS u_confirmed,
           u.{{QbVideoUse.confirmedRevision}} AS u_confirmed_revision, u.{{QbVideoUse.rejectedAt}} AS u_rejected,
           u.{{QbVideoUse.rejectReason}} AS u_reason, u.{{QbVideoUse.revision}} AS u_revision,
           u.{{QbVideoUse.editedAt}} AS u_edited
    FROM {{QbVideoUseFigures}} f
    FULL JOIN {{QbVideoUse}} u ON u.{{QbVideoUse.publicationId}} = f.{{QbVideoUseFigures.publicationId}})
SELECT
    r.pid AS publicationId,
    c.{{QbPublicationCard.name}} AS name,
    c.{{QbPublicationCard.status}} AS publicationStatus,
    c.{{QbPublicationCard.wentOnAt}} AS wentOnAt,
    c.{{QbPublicationCard.endedAt}} AS endedAt,
    r.svid AS sharedVideoId,
    CAST(v.{{QbSharedVideo.title}} AS text) AS videoTitle,
    sp.{{QbSponsor.company}} AS userCompany,
    r.u_id AS videoUseId, r.u_status AS status, r.u_from AS periodFrom, r.u_to AS periodTo,
    r.u_pricing AS pricing, r.u_price AS price, r.u_note AS note, r.u_submitted AS submittedAt,
    r.u_confirmed AS confirmedAt, r.u_confirmed_revision AS confirmedRevision, r.u_rejected AS rejectedAt,
    r.u_reason AS rejectReason, r.u_revision AS revision, r.u_edited AS editedAt,
    CASE WHEN r.f_opens >= k.k THEN r.f_opens END AS opens,
    CASE WHEN r.f_successes >= k.k THEN r.f_successes END AS successes,
    CASE WHEN r.f_goes >= k.k THEN r.f_goes END AS goes,
    CASE WHEN r.f_qualified >= k.k THEN r.f_qualified END AS qualified,
    k.k AS minGroup,
    r.f_refreshed AS refreshedAt,
    r.f_finalised AS finalisedAt,
    CASE r.u_pricing
        WHEN 'FIXED' THEN r.u_price
        WHEN 'PER_MILLE' THEN CASE WHEN r.f_successes >= k.k THEN floor(r.u_price * r.f_successes / 1000) END
        WHEN 'PER_TAKER' THEN CASE WHEN r.f_goes >= k.k THEN r.u_price * r.f_goes END
    END AS amount
FROM rows r
CROSS JOIN params k
LEFT JOIN {{QbPublicationCard}} c ON CAST(c.{{QbPublicationCard.publicationId}} AS text) = r.pid
LEFT JOIN {{QbSharedVideo}} v ON CAST(v.{{QbSharedVideo.sharedVideoId}} AS text) = r.svid
LEFT JOIN {{QbSponsor}} sp ON CAST(sp.{{QbSponsor.userId}} AS text) = r.uid
WHERE (CAST(:publicationId AS text) IS NULL OR r.pid = CAST(:publicationId AS text))
