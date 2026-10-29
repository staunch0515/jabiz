/*---
id: qb.admin.publications
description: Every sponsor's publications with their quiz, state, review and counts, for review and support
entities: [QbPublication, QbQuizVersion, QbPublicationCounter, QbPublicationStartCounter]
datasets:
  QbPublication: urn:jabiz:dataset:default:QbPublication
  QbQuizVersion: urn:jabiz:dataset:default:QbQuizVersion
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
params:
  q: { kind: { type: text, maxLength: 100 }, description: Only publications whose name contains this text }
  inReview: { kind: { type: bool }, description: "Only those waiting for a review, of the publication or of a switch of its version (Q51)" }
results:
  publicationId:     { from: QbPublication.publicationId }
  publisherKind:     { from: QbPublication.publisherKind }
  publisherId:       { from: QbPublication.publisherId }
  quizId:            { from: QbPublication.quizId }
  name:              { from: QbPublication.name }
  quizTitle:         { from: QbQuizVersion.title }
  versionLabel:      { from: QbPublication.versionLabel }
  status:            { from: QbPublication.status }
  audience:          { from: QbPublication.audience }
  regions:           { from: QbPublication.regions }
  starts:            { from: QbPublicationStartCounter.starts }
  completions:       { from: QbPublicationCounter.completions }
  passes:            { from: QbPublicationCounter.passes }
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
  pendingVersionId:    { from: QbPublication.pendingVersionId }
  pendingVersionLabel: { from: QbPublication.pendingVersionLabel }
  pendingRequestId:    { from: QbPublication.pendingRequestId }
  pendingSince:        { from: QbPublication.pendingSince }
  waitingSince:        { from: QbPublication.submittedAt }
list:
  filters: [publicationId, publisherKind, publisherId, quizId, status, startAt, submittedAt]
  sorts:   [editedAt, submittedAt, pendingSince, waitingSince, startAt, name, status]
  defaultSort: { field: editedAt, asc: false }
  key: [publicationId]
permissions: [qb.admin.publication.read]
---*/
-- As qb.sponsor.publications, for every sponsor, without the sponsor's funds: those are the finance administrators'
-- (qb.admin.publication-funds), and the content administrators who review read this one. Both sides of the
-- counters in this one statement (Q10a4).
SELECT
    p.{{QbPublication.publicationId}}       AS publicationId,
    p.{{QbPublication.publisherKind}}         AS publisherKind,
    p.{{QbPublication.publisherId}}           AS publisherId,
    p.{{QbPublication.quizId}}              AS quizId,
    p.{{QbPublication.name}}                AS name,
    v.{{QbQuizVersion.title}}               AS quizTitle,
    p.{{QbPublication.versionLabel}}        AS versionLabel,
    p.{{QbPublication.status}}              AS status,
    p.{{QbPublication.audience}}            AS audience,
    p.{{QbPublication.regions}}             AS regions,
    coalesce(s.{{QbPublicationStartCounter.starts}}, 0) AS starts,
    coalesce(c.{{QbPublicationCounter.completions}}, 0) AS completions,
    coalesce(c.{{QbPublicationCounter.passes}}, 0) AS passes,
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
    p.{{QbPublication.editedAt}}            AS editedAt,
    p.{{QbPublication.pendingVersionId}}    AS pendingVersionId,
    p.{{QbPublication.pendingVersionLabel}} AS pendingVersionLabel,
    p.{{QbPublication.pendingRequestId}}    AS pendingRequestId,
    p.{{QbPublication.pendingSince}}        AS pendingSince,
    -- Since when it waits for a review (Q51): a switch's request, else the submission.
    coalesce(p.{{QbPublication.pendingSince}}, p.{{QbPublication.submittedAt}}) AS waitingSince
FROM {{QbPublication}} p
LEFT JOIN {{QbQuizVersion}} v ON v.{{QbQuizVersion.versionId}} = p.{{QbPublication.versionId}}
LEFT JOIN {{QbPublicationCounter}} c ON c.{{QbPublicationCounter.publicationId}} = p.{{QbPublication.publicationId}}
LEFT JOIN {{QbPublicationStartCounter}} s
    ON s.{{QbPublicationStartCounter.publicationId}} = p.{{QbPublication.publicationId}}
WHERE (CAST(:q AS text) IS NULL OR strpos(lower(p.{{QbPublication.name}}), lower(CAST(:q AS text))) > 0)
  AND (CAST(:inReview AS boolean) IS NOT TRUE OR p.{{QbPublication.status}} = 'PENDING_REVIEW'
       OR p.{{QbPublication.pendingRequestId}} IS NOT NULL)
