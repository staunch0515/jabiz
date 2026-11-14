/*---
id: qb.sponsor.publications
description: The sponsor's own publications (M-34) with their quiz, state, regions and counts; searched by name
entities: [QbPublication, QbQuizVersion, QbPublicationCounter, QbPublicationStartCounter, QbWallet, QbAttempt, QbPublicationCard, QbExposure, SysParam]
datasets:
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbQuizVersion: urn:jabiz:dataset:default:QbQuizVersion
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbPublicationCard: urn:jabiz:dataset:sponsor:QbPublicationCard
  QbExposure: urn:jabiz:dataset:default:QbExposure
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  q: { kind: { type: text, maxLength: 100 }, description: Only publications whose name contains this text }
  exposureLow: { kind: { type: bool }, description: "When true, only the running publications that bought exposures and have few left (the home's to-do item exposureLow counts the same)" }
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
  entryFees:     { kind: { type: monetary, currency: JPY, scale: 0 } }
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
  filters: [status, startAt, fundsShort, publicationId]
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
    -- has none); json, not jsonb: only parsed, never built, for each row read. Q64: the publication's own cover first.
    coalesce(CAST(p.{{QbPublication.cover}} AS text),
        CAST(CAST(v.{{QbQuizVersion.content}} AS json) ->> 'cover' AS text)) AS cover,
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
    coalesce(ef.ef_total, 0)                        AS entryFees,
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
-- Q88b: the entry fees its takers paid (what the attempts record), a total only: per publication listed (the paying
-- attempts' partial index), not all of them.
LEFT JOIN LATERAL (
    SELECT sum(m.{{QbAttempt.entryFee}}) AS ef_total
    FROM {{QbAttempt}} m
    WHERE m.{{QbAttempt.publicationId}} = p.{{QbPublication.publicationId}} AND m.{{QbAttempt.entryFee}} > 0) ef ON true
WHERE (CAST(:q AS text) IS NULL OR strpos(lower(p.{{QbPublication.name}}), lower(CAST(:q AS text))) > 0)
  AND (CAST(:exposureLow AS boolean) IS NOT TRUE OR EXISTS (
    SELECT 1 FROM {{QbPublicationCard}} c
      CROSS JOIN LATERAL (SELECT count(*) AS n FROM {{QbExposure}} e
                          WHERE e.{{QbExposure.publicationId}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)
                            AND e.{{QbExposure.countedAt}} IS NOT NULL) x
      CROSS JOIN LATERAL (SELECT least(CAST(r.{{SysParam.value}} AS numeric), 0.5) AS ratio FROM {{SysParam}} r
                          WHERE r.{{SysParam.paramKey}} = 'qb.exposure.low-ratio'
                            AND r.{{SysParam.value}} ~ '^[0-9]+(\.[0-9]+)?$') q
      WHERE CAST(c.{{QbPublicationCard.publicationId}} AS text) = CAST(p.{{QbPublication.publicationId}} AS text)
        AND c.{{QbPublicationCard.exposurePurchased}} IS NOT NULL
        AND c.{{QbPublicationCard.status}} IN ('SCHEDULED', 'LIVE', 'PAUSED', 'FUNDS_SHORT')
        AND q.ratio > 0
        AND x.n < c.{{QbPublicationCard.exposurePurchased}}
        AND c.{{QbPublicationCard.exposurePurchased}} - x.n <= c.{{QbPublicationCard.exposurePurchased}} * q.ratio))
