/*---
id: qb.admin.video-uses
description: >-
  F-S14: the ledger of shared videos' uses, read only: each use with its two sides, its state and pricing, and the
  figures (each below the minimum group not shown). Headquarters reads every sponsor's, an operator its own sponsors'
  (the users') only
entities: [QbVideoUse, QbVideoUseFigures, QbPublicationCard, QbSharedVideo, QbSponsor, SysParam]
datasets:
  QbVideoUse: urn:jabiz:dataset:default:QbVideoUse
  QbVideoUseFigures: urn:jabiz:dataset:default:QbVideoUseFigures
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSharedVideo: urn:jabiz:dataset:default:QbSharedVideo
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  videoUseId:        { from: QbVideoUse.videoUseId }
  publicationId:     { from: QbVideoUse.publicationId }
  name:              { from: QbPublicationCard.name }
  sponsorId:         { from: QbVideoUse.sponsorId }
  sponsorCompany:    { kind: { type: text, maxLength: 200 } }
  providerId:        { from: QbVideoUse.providerId }
  providerCompany:   { kind: { type: text, maxLength: 200 } }
  videoTitle:        { kind: { type: text } }
  status:            { from: QbVideoUse.status }
  periodFrom:        { from: QbVideoUse.periodFrom }
  periodTo:          { from: QbVideoUse.periodTo }
  pricing:           { from: QbVideoUse.pricing }
  price:             { from: QbVideoUse.price }
  note:              { from: QbVideoUse.note }
  rejectReason:      { from: QbVideoUse.rejectReason }
  confirmedRevision: { from: QbVideoUse.confirmedRevision }
  revision:          { from: QbVideoUse.revision }
  editedAt:          { from: QbVideoUse.editedAt }
  opens:             { kind: { type: numeric, precision: 12, scale: 0 } }
  successes:         { kind: { type: numeric, precision: 12, scale: 0 } }
  goes:              { kind: { type: numeric, precision: 12, scale: 0 } }
  qualified:         { kind: { type: numeric, precision: 12, scale: 0 } }
  minGroup:          { kind: { type: numeric, precision: 9, scale: 0 } }
  finalisedAt:       { from: QbVideoUseFigures.finalisedAt }
  amount:            { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  filters: [status, sponsorId, providerId]
  sorts:   [editedAt]
  defaultSort: { field: editedAt, asc: false }
  key: [videoUseId]
permissions: [qb.admin.video-use.read]
---*/
-- The uses the caller looks after (an operator's session reads its own sponsors' only: the entry scope of the users,
-- the publications' sponsors), never written here. Figures and amount as on the sponsors' pages (Q9e's k, VideoUseRules).
WITH params AS (
    SELECT greatest(3, coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,9}$'
                                         THEN CAST(p.{{SysParam.value}} AS int) END), 5)) AS k
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.stats.min-group')
SELECT
    u.{{QbVideoUse.videoUseId}} AS videoUseId,
    u.{{QbVideoUse.publicationId}} AS publicationId,
    c.{{QbPublicationCard.name}} AS name,
    u.{{QbVideoUse.sponsorId}} AS sponsorId,
    su.{{QbSponsor.company}} AS sponsorCompany,
    u.{{QbVideoUse.providerId}} AS providerId,
    sp.{{QbSponsor.company}} AS providerCompany,
    CAST(v.{{QbSharedVideo.title}} AS text) AS videoTitle,
    u.{{QbVideoUse.status}} AS status,
    u.{{QbVideoUse.periodFrom}} AS periodFrom,
    u.{{QbVideoUse.periodTo}} AS periodTo,
    u.{{QbVideoUse.pricing}} AS pricing,
    u.{{QbVideoUse.price}} AS price,
    u.{{QbVideoUse.note}} AS note,
    u.{{QbVideoUse.rejectReason}} AS rejectReason,
    u.{{QbVideoUse.confirmedRevision}} AS confirmedRevision,
    u.{{QbVideoUse.revision}} AS revision,
    u.{{QbVideoUse.editedAt}} AS editedAt,
    CASE WHEN f.{{QbVideoUseFigures.opens}} >= k.k THEN f.{{QbVideoUseFigures.opens}} END AS opens,
    CASE WHEN f.{{QbVideoUseFigures.successes}} >= k.k THEN f.{{QbVideoUseFigures.successes}} END AS successes,
    CASE WHEN f.{{QbVideoUseFigures.goes}} >= k.k THEN f.{{QbVideoUseFigures.goes}} END AS goes,
    CASE WHEN f.{{QbVideoUseFigures.qualified}} >= k.k THEN f.{{QbVideoUseFigures.qualified}} END AS qualified,
    k.k AS minGroup,
    f.{{QbVideoUseFigures.finalisedAt}} AS finalisedAt,
    CASE u.{{QbVideoUse.pricing}}
        WHEN 'FIXED' THEN u.{{QbVideoUse.price}}
        WHEN 'PER_MILLE' THEN CASE WHEN f.{{QbVideoUseFigures.successes}} >= k.k
            THEN floor(u.{{QbVideoUse.price}} * f.{{QbVideoUseFigures.successes}} / 1000) END
        WHEN 'PER_TAKER' THEN CASE WHEN f.{{QbVideoUseFigures.goes}} >= k.k
            THEN u.{{QbVideoUse.price}} * f.{{QbVideoUseFigures.goes}} END
    END AS amount
FROM {{QbVideoUse}} u
CROSS JOIN params k
LEFT JOIN {{QbVideoUseFigures}} f ON f.{{QbVideoUseFigures.publicationId}} = u.{{QbVideoUse.publicationId}}
LEFT JOIN {{QbPublicationCard}} c ON CAST(c.{{QbPublicationCard.publicationId}} AS text) = u.{{QbVideoUse.publicationId}}
LEFT JOIN {{QbSharedVideo}} v ON CAST(v.{{QbSharedVideo.sharedVideoId}} AS text) = u.{{QbVideoUse.sharedVideoId}}
LEFT JOIN {{QbSponsor}} su ON CAST(su.{{QbSponsor.userId}} AS text) = u.{{QbVideoUse.sponsorId}}
LEFT JOIN {{QbSponsor}} sp ON CAST(sp.{{QbSponsor.userId}} AS text) = u.{{QbVideoUse.providerId}}
