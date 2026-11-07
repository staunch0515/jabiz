/*---
id: qb.sponsor.asset-candidates
description: >-
  What of the sponsor's own content could go into their asset library (F-C1): the materials' files, links and
  articles, the quizzes' covers and introductions, the questions' and options' images, and the publications' covers
  and descriptions; once each, without what the library already has (files by id, texts by hash)
entities: [QbQuiz, QbMaterial, QbMaterialImage, QbQuestion, QbOption, QbPublication, QbAsset]
datasets:
  QbQuiz: urn:jabiz:dataset:sponsor:QbQuiz
  QbMaterial: urn:jabiz:dataset:sponsor:QbMaterial
  QbMaterialImage: urn:jabiz:dataset:sponsor:QbMaterialImage
  QbQuestion: urn:jabiz:dataset:sponsor:QbQuestion
  QbOption: urn:jabiz:dataset:sponsor:QbOption
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbAsset: urn:jabiz:dataset:me:QbAsset
results:
  candidateKey:  { kind: { type: text, maxLength: 200 } }
  kind:          { from: QbAsset.kind }
  usage:         { from: QbAsset.usage }
  sourceKind:    { kind: { type: text, maxLength: 24 } }
  sourceId:      { kind: { type: text, maxLength: 36 } }
  quizId:        { kind: { type: text, maxLength: 36 } }
  publicationId: { kind: { type: text, maxLength: 36 } }
  name:          { from: QbAsset.name }
  description:   { from: QbAsset.description }
  language:      { from: QbAsset.language }
  image:         { from: QbAsset.image }
  pdf:           { from: QbAsset.pdf }
  audio:         { from: QbAsset.audio }
  url:           { from: QbAsset.url }
  durationSec:   { from: QbAsset.durationSec }
  body:          { from: QbAsset.body }
  contentHash:   { from: QbAsset.contentHash }
  editedAt:      { from: QbQuiz.editedAt }
list:
  filters: [kind, usage, sourceKind]
  sorts:   [editedAt, name, kind]
  defaultSort: { field: editedAt, asc: false }
  key: [candidateKey]
permissions: [qb.content.write]
---*/
-- Read only when the sponsor opens the collection; never kept in step. The datasets give the sponsor's own rows: their
-- quizzes not removed (a material, question or option counts only through one), and all their publications, drafts and
-- submitted. Materials are the base language's (a language's own version is not one to keep twice). The kinds and uses
-- are the assets' (AssetKind, AssetRules.USAGES); the key and hash are AssetRules.dedupeKey / contentHash: a file by
-- its id, a text by its kind, use and the SHA-256 of its UTF-8 text as stored. Content that appears in several places
-- is listed once: from the first place of quiz, material, its images, question, option, publication (the latest
-- edited first).
-- A referral's description is listed only when it fits a text asset (the publication's own has no limit of its own).
WITH quiz AS (
    SELECT z.{{QbQuiz.quizId}} AS qid, z.{{QbQuiz.title}} AS title, z.{{QbQuiz.language}} AS lang,
           z.{{QbQuiz.intro}} AS intro, z.{{QbQuiz.cover}} AS cover, z.{{QbQuiz.editedAt}} AS edited
    FROM {{QbQuiz}} z),
found AS (
    SELECT CAST(m.{{QbMaterial.kind}} AS text) AS kind, CAST(NULL AS text) AS usage, 'MATERIAL' AS source_kind,
           CAST(m.{{QbMaterial.materialId}} AS text) AS source_id, q.qid AS quiz_id, CAST(NULL AS uuid) AS pub_id,
           coalesce(m.{{QbMaterial.title}}, q.title) AS name, m.{{QbMaterial.description}} AS description,
           q.lang AS lang, CAST(NULL AS uuid) AS image, m.{{QbMaterial.pdf}} AS pdf, m.{{QbMaterial.audio}} AS audio,
           m.{{QbMaterial.url}} AS url,
           CASE WHEN m.{{QbMaterial.kind}} = 'VIDEO_LINK' THEN m.{{QbMaterial.durationSec}} END AS duration_sec,
           m.{{QbMaterial.body}} AS body, q.edited AS edited, 2 AS place
    FROM {{QbMaterial}} m
    JOIN quiz q ON q.qid = m.{{QbMaterial.quizId}}
    WHERE m.{{QbMaterial.variantOf}} IS NULL
      AND m.{{QbMaterial.kind}} IN ('ARTICLE', 'LINK', 'VIDEO_LINK', 'PDF', 'AUDIO')
    UNION ALL
    SELECT 'IMAGE', NULL, 'MATERIAL', CAST(i.{{QbMaterialImage.materialId}} AS text), q.qid, NULL,
           coalesce(m.{{QbMaterial.title}}, q.title), i.{{QbMaterialImage.caption}}, q.lang,
           i.{{QbMaterialImage.image}}, NULL, NULL, NULL, NULL, NULL, q.edited, 3
    FROM {{QbMaterialImage}} i
    JOIN {{QbMaterial}} m ON m.{{QbMaterial.materialId}} = i.{{QbMaterialImage.materialId}}
    JOIN quiz q ON q.qid = i.{{QbMaterialImage.quizId}}
    WHERE m.{{QbMaterial.variantOf}} IS NULL
    UNION ALL
    SELECT 'IMAGE', NULL, 'QUIZ_COVER', CAST(q.qid AS text), q.qid, NULL, q.title, NULL, q.lang, q.cover,
           NULL, NULL, NULL, NULL, NULL, q.edited, 1
    FROM quiz q
    UNION ALL
    SELECT 'TEXT', 'QUIZ_INTRO', 'QUIZ_INTRO', CAST(q.qid AS text), q.qid, NULL, q.title, NULL, q.lang, NULL,
           NULL, NULL, NULL, NULL, q.intro, q.edited, 1
    FROM quiz q
    UNION ALL
    SELECT 'IMAGE', NULL, 'QUESTION_IMAGE', CAST(u.{{QbQuestion.questionId}} AS text), q.qid, NULL, q.title, NULL,
           q.lang, u.{{QbQuestion.image}}, NULL, NULL, NULL, NULL, NULL, q.edited, 4
    FROM {{QbQuestion}} u
    JOIN quiz q ON q.qid = u.{{QbQuestion.quizId}}
    UNION ALL
    SELECT 'IMAGE', NULL, 'OPTION_IMAGE', CAST(o.{{QbOption.optionId}} AS text), q.qid, NULL, q.title, NULL,
           q.lang, o.{{QbOption.image}}, NULL, NULL, NULL, NULL, NULL, q.edited, 5
    FROM {{QbOption}} o
    JOIN quiz q ON q.qid = o.{{QbOption.quizId}}
    UNION ALL
    SELECT x.kind, x.usage, x.source_kind, CAST(p.{{QbPublication.publicationId}} AS text), q.qid,
           p.{{QbPublication.publicationId}}, p.{{QbPublication.name}}, NULL,
           coalesce(nullif(split_part(coalesce(p.{{QbPublication.languages}}, ''), ',', 1), ''), q.lang, 'ja'),
           x.image, NULL, NULL, NULL, NULL, x.body, p.{{QbPublication.editedAt}}, 6
    FROM {{QbPublication}} p
    LEFT JOIN quiz q ON q.qid = p.{{QbPublication.quizId}}
    CROSS JOIN LATERAL (VALUES
        ('IMAGE', CAST(NULL AS text), 'PUBLICATION_COVER', p.{{QbPublication.cover}}, CAST(NULL AS text)),
        ('TEXT', 'REDEEM_DESCRIPTION', 'PUBLICATION_REDEEM', NULL, p.{{QbPublication.redeemDescription}}),
        ('TEXT', 'DIGITAL_DESCRIPTION', 'PUBLICATION_DIGITAL', NULL, p.{{QbPublication.digitalDescription}}),
        ('TEXT', 'REFERRAL_DESCRIPTION', 'PUBLICATION_REFERRAL', NULL,
         CASE WHEN p.{{QbPublication.referral}} IS NOT NULL
              THEN CAST(p.{{QbPublication.referral}} AS jsonb) ->> 'description' END)
    ) AS x(kind, usage, source_kind, image, body)
    WHERE x.usage IS DISTINCT FROM 'REFERRAL_DESCRIPTION' OR char_length(x.body) <= 500),
keyed AS (
    SELECT f.*,
           CASE WHEN coalesce(f.image, f.pdf, f.audio) IS NOT NULL
                THEN 'f:' || CAST(coalesce(f.image, f.pdf, f.audio) AS text)
                ELSE 't:' || f.kind || ':' || coalesce(f.usage, '') || ':'
                     || encode(sha256(convert_to(coalesce(f.url, f.body), 'UTF8')), 'hex') END AS dk,
           CASE WHEN coalesce(f.image, f.pdf, f.audio) IS NULL
                THEN encode(sha256(convert_to(coalesce(f.url, f.body), 'UTF8')), 'hex') END AS hash
    FROM found f
    WHERE coalesce(f.image, f.pdf, f.audio) IS NOT NULL
       OR coalesce(nullif(f.url, ''), nullif(btrim(f.body), '')) IS NOT NULL),
library AS (
    SELECT CASE WHEN coalesce(a.{{QbAsset.image}}, a.{{QbAsset.pdf}}, a.{{QbAsset.audio}}) IS NOT NULL
                THEN 'f:' || CAST(coalesce(a.{{QbAsset.image}}, a.{{QbAsset.pdf}}, a.{{QbAsset.audio}}) AS text)
                WHEN a.{{QbAsset.contentHash}} IS NOT NULL
                THEN 't:' || a.{{QbAsset.kind}} || ':'
                     || coalesce(CASE a.{{QbAsset.kind}} WHEN 'TEXT' THEN a.{{QbAsset.usage}}
                                                         WHEN 'PRIZE' THEN a.{{QbAsset.prizeMethod}} END, '')
                     || ':' || a.{{QbAsset.contentHash}} END AS dk
    FROM {{QbAsset}} a),
picked AS (
    SELECT DISTINCT ON (k.dk) k.*
    FROM keyed k
    WHERE NOT EXISTS (SELECT 1 FROM library l WHERE l.dk = k.dk)
    ORDER BY k.dk, k.place, k.edited DESC, k.source_id)
SELECT
    k.dk                              AS candidateKey,
    k.kind                            AS kind,
    k.usage                           AS usage,
    k.source_kind                     AS sourceKind,
    k.source_id                       AS sourceId,
    CAST(k.quiz_id AS text)           AS quizId,
    CAST(k.pub_id AS text)            AS publicationId,
    k.name                            AS name,
    k.description                     AS description,
    k.lang                            AS language,
    k.image                           AS image,
    k.pdf                             AS pdf,
    k.audio                           AS audio,
    k.url                             AS url,
    k.duration_sec                    AS durationSec,
    k.body                            AS body,
    k.hash                            AS contentHash,
    k.edited                          AS editedAt
FROM picked k
