/*---
id: qb.admin.sponsor-pages
description: >-
  Sponsors' pages (Q60a) with the sponsor's company, last edited first: the review queue (status PENDING), the list
  to take pages down from, and one page's content (userId); the logo and the story are the onboarding's avatar and
  introduction, as the page shows them (Q65b); an operator's session sees its own sponsors' only
entities: [QbSponsorPage, QbSponsor, QbSponsorOperator, QbOperator]
datasets:
  QbSponsorPage: urn:jabiz:dataset:default:QbSponsorPage
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperator: urn:jabiz:dataset:default:QbOperator
params:
  userId: { like: QbSponsorPage.userId, description: One sponsor's page }
results:
  userId:            { from: QbSponsorPage.userId }
  handle:            { from: QbSponsorPage.handle }
  language:          { from: QbSponsorPage.language }
  headline:          { from: QbSponsorPage.headline }
  languages:         { from: QbSponsorPage.languages }
  translations:      { from: QbSponsorPage.translations }
  story:             { from: QbSponsor.intro }
  logo:              { from: QbSponsor.avatar }
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
  # F-S1b: the sponsor's operator now (every sponsor has one), for the platform's filter by operator.
  operatorId:   { from: QbSponsorOperator.operatorId }
  operatorName: { from: QbOperator.name }
list:
  filters: [status, language, handle, company, operatorId]
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
    -- Q73c: the languages offered and the headline in each, reviewed with it.
    p.{{QbSponsorPage.languages}}         AS languages,
    p.{{QbSponsorPage.translations}}      AS translations,
    s.{{QbSponsor.intro}}                 AS story,
    s.{{QbSponsor.avatar}}                AS logo,
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
    s.{{QbSponsor.company}}               AS company,
    qso.{{QbSponsorOperator.operatorId}} AS operatorId,
    qop.{{QbOperator.name}}               AS operatorName
FROM {{QbSponsorPage}} p
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = p.{{QbSponsorPage.userId}}
LEFT JOIN {{QbSponsorOperator}} qso ON qso.{{QbSponsorOperator.userId}} = p.{{QbSponsorPage.userId}}
LEFT JOIN {{QbOperator}} qop ON qop.{{QbOperator.operatorId}} = qso.{{QbSponsorOperator.operatorId}}
WHERE (CAST(:userId AS text) IS NULL OR p.{{QbSponsorPage.userId}} = CAST(:userId AS text))
