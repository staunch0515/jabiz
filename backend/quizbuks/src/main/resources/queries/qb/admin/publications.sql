/*---
id: qb.admin.publications
description: Every sponsor's publications with their quiz, state, review and counts, for review and support
entities: [QbPublication, QbQuizVersion, QbPublicationCounter]
datasets:
  QbPublication: urn:jabiz:dataset:default:QbPublication
  QbQuizVersion: urn:jabiz:dataset:default:QbQuizVersion
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
params:
  q: { kind: { type: text, maxLength: 100 }, description: Only publications whose name contains this text }
results:
  publicationId:     { from: QbPublication.publicationId }
  sponsorId:         { from: QbPublication.sponsorId }
  name:              { from: QbPublication.name }
  quizTitle:         { from: QbQuizVersion.title }
  versionLabel:      { from: QbPublication.versionLabel }
  status:            { from: QbPublication.status }
  regions:           { from: QbPublication.regions }
  starts:            { from: QbPublicationCounter.starts }
  winners:           { from: QbPublicationCounter.winners }
  maxWinners:        { from: QbPublication.maxWinners }
  spent:             { from: QbPublicationCounter.spent }
  budgetCap:         { from: QbPublication.budgetCap }
  maxSpend:          { from: QbPublication.maxSpend }
  startAt:           { from: QbPublication.startAt }
  endAt:             { from: QbPublication.endAt }
  approvalRequestId: { from: QbPublication.approvalRequestId }
  submittedAt:       { from: QbPublication.submittedAt }
  rejectReason:      { from: QbPublication.rejectReason }
  rejectCode:        { from: QbPublication.rejectCode }
  editedAt:          { from: QbPublication.editedAt }
list:
  filters: [sponsorId, status, startAt, submittedAt]
  sorts:   [editedAt, submittedAt, startAt, name]
  defaultSort: { field: editedAt, asc: false }
  key: [publicationId]
permissions: [qb.admin.publication.read]
---*/
-- As qb.sponsor.publications, for every sponsor.
SELECT
    p.{{QbPublication.publicationId}}       AS publicationId,
    p.{{QbPublication.sponsorId}}           AS sponsorId,
    p.{{QbPublication.name}}                AS name,
    v.{{QbQuizVersion.title}}               AS quizTitle,
    p.{{QbPublication.versionLabel}}        AS versionLabel,
    p.{{QbPublication.status}}              AS status,
    p.{{QbPublication.regions}}             AS regions,
    coalesce(c.{{QbPublicationCounter.starts}}, 0)  AS starts,
    coalesce(c.{{QbPublicationCounter.winners}}, 0) AS winners,
    p.{{QbPublication.maxWinners}}          AS maxWinners,
    coalesce(c.{{QbPublicationCounter.spent}}, 0)   AS spent,
    p.{{QbPublication.budgetCap}}           AS budgetCap,
    p.{{QbPublication.maxSpend}}            AS maxSpend,
    p.{{QbPublication.startAt}}             AS startAt,
    p.{{QbPublication.endAt}}               AS endAt,
    p.{{QbPublication.approvalRequestId}}   AS approvalRequestId,
    p.{{QbPublication.submittedAt}}         AS submittedAt,
    p.{{QbPublication.rejectReason}}        AS rejectReason,
    p.{{QbPublication.rejectCode}}          AS rejectCode,
    p.{{QbPublication.editedAt}}            AS editedAt
FROM {{QbPublication}} p
LEFT JOIN {{QbQuizVersion}} v ON v.{{QbQuizVersion.versionId}} = p.{{QbPublication.versionId}}
LEFT JOIN {{QbPublicationCounter}} c ON c.{{QbPublicationCounter.publicationId}} = p.{{QbPublication.publicationId}}
WHERE (CAST(:q AS text) IS NULL OR strpos(lower(p.{{QbPublication.name}}), lower(CAST(:q AS text))) > 0)
