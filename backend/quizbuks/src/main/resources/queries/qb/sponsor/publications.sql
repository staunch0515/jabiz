/*---
id: qb.sponsor.publications
description: The sponsor's own publications (M-34) with their quiz, state, regions and counts; searched by name
entities: [QbPublication, QbQuizVersion, QbPublicationCounter, QbPublicationStartCounter, QbWallet]
datasets:
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbQuizVersion: urn:jabiz:dataset:default:QbQuizVersion
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
  QbWallet: urn:jabiz:dataset:default:QbWallet
params:
  q: { kind: { type: text, maxLength: 100 }, description: Only publications whose name contains this text }
results:
  publicationId: { from: QbPublication.publicationId }
  name:          { from: QbPublication.name }
  quizTitle:     { from: QbQuizVersion.title }
  cover:         { kind: { type: text, maxLength: 36 } }
  versionLabel:  { from: QbPublication.versionLabel }
  status:        { from: QbPublication.status }
  rewardMethod:  { from: QbPublication.rewardMethod }
  audience:      { from: QbPublication.audience }
  regions:       { from: QbPublication.regions }
  starts:        { from: QbPublicationStartCounter.starts }
  completions:       { from: QbPublicationCounter.completions }
  passes:            { from: QbPublicationCounter.passes }
  winners:       { from: QbPublicationCounter.winners }
  maxWinners:    { from: QbPublication.maxWinners }
  spent:         { from: QbPublicationCounter.spent }
  fundsShort:        { kind: { type: bool } }
  pendingCount:      { from: QbWallet.pendingCount }
  pendingAmount:     { from: QbWallet.pendingAmount }
  budgetCap:     { from: QbPublication.budgetCap }
  maxSpend:      { from: QbPublication.maxSpend }
  startAt:       { from: QbPublication.startAt }
  endAt:         { from: QbPublication.endAt }
  rejectReason:  { from: QbPublication.rejectReason }
  rejectCode:    { from: QbPublication.rejectCode }
  editedAt:      { from: QbPublication.editedAt }
list:
  filters: [status, startAt, fundsShort]
  sorts:   [editedAt, startAt, name]
  defaultSort: { field: editedAt, asc: false }
  key: [publicationId]
permissions: [qb.publication.write]
---*/
-- The quiz's title as the version keeps it (a draft may have no version yet); counts are those of a publication that
-- went on, 0 before; the starts from the counters' starts' side and the rest from the completions' side, in this one
-- statement (Q10a4). The text is found as written: strpos takes it literally, so % _ and \ match themselves.
SELECT
    p.{{QbPublication.publicationId}}       AS publicationId,
    p.{{QbPublication.name}}                AS name,
    v.{{QbQuizVersion.title}}               AS quizTitle,
    -- Q34b: the card of the list shows the quiz's cover as the version's snapshot keeps it (a draft without a version
    -- has none); json, not jsonb: only parsed, never built, for each row read.
    CAST(CAST(v.{{QbQuizVersion.content}} AS json) ->> 'cover' AS text) AS cover,
    p.{{QbPublication.versionLabel}}        AS versionLabel,
    p.{{QbPublication.status}}              AS status,
    p.{{QbPublication.rewardMethod}}        AS rewardMethod,
    p.{{QbPublication.audience}}            AS audience,
    p.{{QbPublication.regions}}             AS regions,
    coalesce(s.{{QbPublicationStartCounter.starts}}, 0) AS starts,
    coalesce(c.{{QbPublicationCounter.completions}}, 0) AS completions,
    coalesce(c.{{QbPublicationCounter.passes}}, 0) AS passes,
    coalesce(c.{{QbPublicationCounter.winners}}, 0) AS winners,
    p.{{QbPublication.maxWinners}}          AS maxWinners,
    coalesce(c.{{QbPublicationCounter.spent}}, 0)   AS spent,
    (p.{{QbPublication.status}} = 'FUNDS_SHORT'
     OR (p.{{QbPublication.status}} = 'LIVE' AND (coalesce(w.{{QbWallet.pendingCount}}, 0) > 0
         OR coalesce(w.{{QbWallet.balance}} - w.{{QbWallet.held}}, 0) < p.{{QbPublication.maxReward}}))) AS fundsShort,
    coalesce(w.{{QbWallet.pendingCount}}, 0)  AS pendingCount,
    coalesce(w.{{QbWallet.pendingAmount}}, 0) AS pendingAmount,
    p.{{QbPublication.budgetCap}}           AS budgetCap,
    p.{{QbPublication.maxSpend}}            AS maxSpend,
    p.{{QbPublication.startAt}}             AS startAt,
    p.{{QbPublication.endAt}}               AS endAt,
    p.{{QbPublication.rejectReason}}        AS rejectReason,
    p.{{QbPublication.rejectCode}}          AS rejectCode,
    p.{{QbPublication.editedAt}}            AS editedAt
FROM {{QbPublication}} p
LEFT JOIN {{QbQuizVersion}} v ON v.{{QbQuizVersion.versionId}} = p.{{QbPublication.versionId}}
LEFT JOIN {{QbPublicationCounter}} c ON c.{{QbPublicationCounter.publicationId}} = p.{{QbPublication.publicationId}}
LEFT JOIN {{QbPublicationStartCounter}} s
    ON s.{{QbPublicationStartCounter.publicationId}} = p.{{QbPublication.publicationId}}
LEFT JOIN {{QbWallet}} w ON w.{{QbWallet.ownerId}} = p.{{QbPublication.publisherId}} AND w.{{QbWallet.kind}} = 'SPONSOR'
WHERE (CAST(:q AS text) IS NULL OR strpos(lower(p.{{QbPublication.name}}), lower(CAST(:q AS text))) > 0)
