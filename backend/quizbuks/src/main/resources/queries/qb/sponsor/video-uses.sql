/*---
id: qb.sponsor.video-uses
description: >-
  F-S14: the user's side of shared videos' uses: the Sponsor's publications that use another Sponsor's shared video,
  each with the ledger's use (none yet: not written), the figures counted every day (final once it ended; each below
  the minimum group not shown) and the reference amount by them
entities: [QbPublicationCard, QbVideoUse, QbVideoUseFigures, QbSharedVideo, QbSponsor, SysParam]
datasets:
  QbPublicationCard: urn:jabiz:dataset:sponsor:QbPublicationCard
  QbVideoUse: urn:jabiz:dataset:sponsor:QbVideoUse
  QbVideoUseFigures: urn:jabiz:dataset:default:QbVideoUseFigures
  QbSharedVideo: urn:jabiz:dataset:default:QbSharedVideo
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  publicationId: { kind: { type: text, maxLength: 36 }, description: "Only this publication (its page); all of the Sponsor's when not given" }
results:
  publicationId:     { from: QbPublicationCard.publicationId }
  name:              { from: QbPublicationCard.name }
  publicationStatus: { from: QbPublicationCard.status }
  wentOnAt:          { from: QbPublicationCard.wentOnAt }
  endAt:             { from: QbPublicationCard.endAt }
  endedAt:           { from: QbPublicationCard.endedAt }
  sharedVideoId:     { from: QbPublicationCard.sharedVideoId }
  videoTitle:        { kind: { type: text } }
  providerCompany:   { from: QbSponsor.company }
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
  cutoffAt:          { from: QbVideoUseFigures.cutoffAt }
  finalisedAt:       { from: QbVideoUseFigures.finalisedAt }
  amount:            { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  filters: [status]
  sorts:   [wentOnAt]
  defaultSort: { field: wentOnAt, asc: false }
  key: [publicationId]
permissions: [qb.stats.read]
---*/
-- The Sponsor's own cards (their dataset holds the caller's only) whose video is another's shared video
-- (videouse.VideoUseRules.othersVideo), the use of each (the caller's side), and its figures. Each figure below k is
-- not shown (Q9e: k is qb.stats.min-group if a whole number, else 5, never below 3, as in every analytics template);
-- the reference amount is VideoUseRules.amount: the fixed price, the price per thousand successes rounded down, the
-- price per taker who went on to answer, none when the figure it is by is not shown.
WITH params AS (
    SELECT greatest(3, coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,9}$'
                                         THEN CAST(p.{{SysParam.value}} AS int) END), 5)) AS k
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.stats.min-group')
SELECT
    c.{{QbPublicationCard.publicationId}} AS publicationId,
    c.{{QbPublicationCard.name}} AS name,
    c.{{QbPublicationCard.status}} AS publicationStatus,
    c.{{QbPublicationCard.wentOnAt}} AS wentOnAt,
    c.{{QbPublicationCard.endAt}} AS endAt,
    c.{{QbPublicationCard.endedAt}} AS endedAt,
    c.{{QbPublicationCard.sharedVideoId}} AS sharedVideoId,
    CAST(v.{{QbSharedVideo.title}} AS text) AS videoTitle,
    sp.{{QbSponsor.company}} AS providerCompany,
    u.{{QbVideoUse.videoUseId}} AS videoUseId,
    u.{{QbVideoUse.status}} AS status,
    u.{{QbVideoUse.periodFrom}} AS periodFrom,
    u.{{QbVideoUse.periodTo}} AS periodTo,
    u.{{QbVideoUse.pricing}} AS pricing,
    u.{{QbVideoUse.price}} AS price,
    u.{{QbVideoUse.note}} AS note,
    u.{{QbVideoUse.submittedAt}} AS submittedAt,
    u.{{QbVideoUse.confirmedAt}} AS confirmedAt,
    u.{{QbVideoUse.confirmedRevision}} AS confirmedRevision,
    u.{{QbVideoUse.rejectedAt}} AS rejectedAt,
    u.{{QbVideoUse.rejectReason}} AS rejectReason,
    u.{{QbVideoUse.revision}} AS revision,
    u.{{QbVideoUse.editedAt}} AS editedAt,
    CASE WHEN f.{{QbVideoUseFigures.opens}} >= k.k THEN f.{{QbVideoUseFigures.opens}} END AS opens,
    CASE WHEN f.{{QbVideoUseFigures.successes}} >= k.k THEN f.{{QbVideoUseFigures.successes}} END AS successes,
    CASE WHEN f.{{QbVideoUseFigures.goes}} >= k.k THEN f.{{QbVideoUseFigures.goes}} END AS goes,
    CASE WHEN f.{{QbVideoUseFigures.qualified}} >= k.k THEN f.{{QbVideoUseFigures.qualified}} END AS qualified,
    k.k AS minGroup,
    f.{{QbVideoUseFigures.refreshedAt}} AS refreshedAt,
    f.{{QbVideoUseFigures.cutoffAt}} AS cutoffAt,
    f.{{QbVideoUseFigures.finalisedAt}} AS finalisedAt,
    CASE u.{{QbVideoUse.pricing}}
        WHEN 'FIXED' THEN u.{{QbVideoUse.price}}
        WHEN 'PER_MILLE' THEN CASE WHEN f.{{QbVideoUseFigures.successes}} >= k.k
            THEN floor(u.{{QbVideoUse.price}} * f.{{QbVideoUseFigures.successes}} / 1000) END
        WHEN 'PER_TAKER' THEN CASE WHEN f.{{QbVideoUseFigures.goes}} >= k.k
            THEN u.{{QbVideoUse.price}} * f.{{QbVideoUseFigures.goes}} END
    END AS amount
FROM {{QbPublicationCard}} c
CROSS JOIN params k
LEFT JOIN {{QbVideoUse}} u
       ON u.{{QbVideoUse.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)
LEFT JOIN {{QbVideoUseFigures}} f
       ON f.{{QbVideoUseFigures.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)
LEFT JOIN {{QbSharedVideo}} v ON v.{{QbSharedVideo.sharedVideoId}} = c.{{QbPublicationCard.sharedVideoId}}
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = c.{{QbPublicationCard.videoProviderId}}
WHERE c.{{QbPublicationCard.sharedVideoId}} IS NOT NULL
  AND c.{{QbPublicationCard.videoProviderId}} IS NOT NULL
  AND c.{{QbPublicationCard.videoProviderId}} <> c.{{QbPublicationCard.publisherId}}
  AND (CAST(:publicationId AS text) IS NULL
       OR CAST(c.{{QbPublicationCard.publicationId}} AS text) = CAST(:publicationId AS text))
