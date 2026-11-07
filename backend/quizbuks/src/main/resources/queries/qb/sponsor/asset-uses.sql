/*---
id: qb.sponsor.asset-uses
description: >-
  Where the sponsor's assets were copied to (F-C1b): each place with its asset, the quiz or publication it is in, and
  whether the asset changed since it was copied ("older content")
entities: [QbAssetUse, QbAsset, QbQuiz, QbMaterial, QbQuestion, QbOption, QbPublication]
datasets:
  QbAssetUse: urn:jabiz:dataset:me:QbAssetUse
  QbAsset: urn:jabiz:dataset:me:QbAsset
  QbQuiz: urn:jabiz:dataset:sponsor:QbQuiz
  QbMaterial: urn:jabiz:dataset:sponsor:QbMaterial
  QbQuestion: urn:jabiz:dataset:sponsor:QbQuestion
  QbOption: urn:jabiz:dataset:sponsor:QbOption
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
results:
  useId:           { from: QbAssetUse.useId }
  assetId:         { from: QbAssetUse.assetId }
  assetName:       { from: QbAsset.name }
  assetKind:       { from: QbAsset.kind }
  targetKind:      { from: QbAssetUse.targetKind }
  targetId:        { from: QbAssetUse.targetId }
  quizId:          { kind: { type: text, maxLength: 36 } }
  publicationId:   { kind: { type: text, maxLength: 36 } }
  targetName:      { kind: { type: text, maxLength: 200 } }
  assetRevision:   { from: QbAssetUse.assetRevision }
  currentRevision: { from: QbAsset.revision }
  stale:           { kind: { type: bool } }
  usedAt:          { from: QbAssetUse.usedAt }
list:
  filters: [assetId, targetKind, stale]
  sorts:   [usedAt, assetName, targetKind]
  defaultSort: { field: usedAt, asc: false }
  key: [useId]
permissions: [qb.content.write]
---*/
-- The datasets give the sponsor's own rows only. A use's asset always exists (deleting an asset deletes its uses).
-- The place's name is cheap to tell: a quiz's places say its title (a material its own title first), a publication's
-- its name; a place in a removed quiz has none. A use is "older content" when the asset's revision moved on since.
SELECT
    u.{{QbAssetUse.useId}}                                     AS useId,
    u.{{QbAssetUse.assetId}}                                   AS assetId,
    a.{{QbAsset.name}}                                         AS assetName,
    a.{{QbAsset.kind}}                                         AS assetKind,
    u.{{QbAssetUse.targetKind}}                                AS targetKind,
    u.{{QbAssetUse.targetId}}                                  AS targetId,
    CAST(z.{{QbQuiz.quizId}} AS text)                          AS quizId,
    CAST(p.{{QbPublication.publicationId}} AS text)            AS publicationId,
    coalesce(m.{{QbMaterial.title}}, z.{{QbQuiz.title}}, p.{{QbPublication.name}}) AS targetName,
    u.{{QbAssetUse.assetRevision}}                             AS assetRevision,
    a.{{QbAsset.revision}}                                     AS currentRevision,
    a.{{QbAsset.revision}} > u.{{QbAssetUse.assetRevision}}    AS stale,
    u.{{QbAssetUse.usedAt}}                                    AS usedAt
FROM {{QbAssetUse}} u
JOIN {{QbAsset}} a ON a.{{QbAsset.assetId}} = u.{{QbAssetUse.assetId}}
LEFT JOIN {{QbMaterial}} m
       ON u.{{QbAssetUse.targetKind}} = 'MATERIAL'
      AND m.{{QbMaterial.materialId}} = CAST(u.{{QbAssetUse.targetId}} AS uuid)
LEFT JOIN {{QbQuestion}} q
       ON u.{{QbAssetUse.targetKind}} = 'QUESTION_IMAGE'
      AND q.{{QbQuestion.questionId}} = CAST(u.{{QbAssetUse.targetId}} AS uuid)
LEFT JOIN {{QbOption}} o
       ON u.{{QbAssetUse.targetKind}} = 'OPTION_IMAGE'
      AND o.{{QbOption.optionId}} = CAST(u.{{QbAssetUse.targetId}} AS uuid)
LEFT JOIN {{QbQuiz}} z
       ON z.{{QbQuiz.quizId}} = CASE WHEN u.{{QbAssetUse.targetKind}} IN ('QUIZ_COVER', 'QUIZ_INTRO')
                                     THEN CAST(u.{{QbAssetUse.targetId}} AS uuid)
                                     ELSE coalesce(m.{{QbMaterial.quizId}}, q.{{QbQuestion.quizId}},
                                                   o.{{QbOption.quizId}}) END
LEFT JOIN {{QbPublication}} p
       ON u.{{QbAssetUse.targetKind}} IN ('PUBLICATION_COVER', 'PUBLICATION_REDEEM', 'PUBLICATION_DIGITAL',
                                         'PUBLICATION_REFERRAL', 'PUBLICATION_PRIZE')
      AND p.{{QbPublication.publicationId}} = CAST(u.{{QbAssetUse.targetId}} AS uuid)
