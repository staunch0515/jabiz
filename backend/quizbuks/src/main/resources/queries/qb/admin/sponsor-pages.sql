/*---
id: qb.admin.sponsor-pages
description: >-
  Sponsors' pages (Q60a) with the sponsor's company, last edited first: the review queue (status PENDING), the list
  to take pages down from, and one page's content (userId); an operator's session sees its own sponsors' only
entities: [QbSponsorPage, QbSponsor]
datasets:
  QbSponsorPage: urn:jabiz:dataset:default:QbSponsorPage
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  userId: { like: QbSponsorPage.userId, description: One sponsor's page }
results:
  userId:            { from: QbSponsorPage.userId }
  handle:            { from: QbSponsorPage.handle }
  language:          { from: QbSponsorPage.language }
  headline:          { from: QbSponsorPage.headline }
  story:             { from: QbSponsorPage.story }
  logo:              { from: QbSponsorPage.logo }
  cover:             { from: QbSponsorPage.cover }
  website:           { from: QbSponsorPage.website }
  status:            { from: QbSponsorPage.status }
  approvalRequestId: { from: QbSponsorPage.approvalRequestId }
  submittedAt:       { from: QbSponsorPage.submittedAt }
  publishedAt:       { from: QbSponsorPage.publishedAt }
  rejectReason:      { from: QbSponsorPage.rejectReason }
  takenDownAt:       { from: QbSponsorPage.takenDownAt }
  takedownReason:    { from: QbSponsorPage.takedownReason }
  reviewRequired:    { from: QbSponsorPage.reviewRequired }
  editedAt:          { from: QbSponsorPage.editedAt }
  company:           { from: QbSponsor.company }
list:
  filters: [status, language, handle, company]
  sorts:   [editedAt, submittedAt, publishedAt, handle, company]
  defaultSort: { field: editedAt, asc: false }
  key: [userId]
permissions: [qb.admin.publication.read]
---*/
-- Both datasets are limited to the operator's sponsors for an operator's session (Q59c-2). Deciding a review is the
-- platform's approval panel; taking a page down is QB_SPONSOR_PAGE_TAKEDOWN (headquarters) or the operator's process.
SELECT
    p.{{QbSponsorPage.userId}}            AS userId,
    p.{{QbSponsorPage.handle}}            AS handle,
    p.{{QbSponsorPage.language}}          AS language,
    p.{{QbSponsorPage.headline}}          AS headline,
    p.{{QbSponsorPage.story}}             AS story,
    p.{{QbSponsorPage.logo}}              AS logo,
    p.{{QbSponsorPage.cover}}             AS cover,
    p.{{QbSponsorPage.website}}           AS website,
    p.{{QbSponsorPage.status}}            AS status,
    p.{{QbSponsorPage.approvalRequestId}} AS approvalRequestId,
    p.{{QbSponsorPage.submittedAt}}       AS submittedAt,
    p.{{QbSponsorPage.publishedAt}}       AS publishedAt,
    p.{{QbSponsorPage.rejectReason}}      AS rejectReason,
    p.{{QbSponsorPage.takenDownAt}}       AS takenDownAt,
    p.{{QbSponsorPage.takedownReason}}    AS takedownReason,
    p.{{QbSponsorPage.reviewRequired}}    AS reviewRequired,
    p.{{QbSponsorPage.editedAt}}          AS editedAt,
    s.{{QbSponsor.company}}               AS company
FROM {{QbSponsorPage}} p
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = p.{{QbSponsorPage.userId}}
WHERE (CAST(:userId AS text) IS NULL OR p.{{QbSponsorPage.userId}} = CAST(:userId AS text))
