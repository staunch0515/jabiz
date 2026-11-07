/*---
id: qb.system.usage-measure
description: >-
  One sponsor's usage now, one row per meter of usage.UsageMeters (F-C2; the startup check UsageMetersCheck keeps the
  two lists the same): the files they uploaded (as uploaded, without variants; a file is one row of the platform's
  files however many places use it), their assets and the text in them, their quizzes, versions and materials, and
  the month's AI tokens of their jobs
entities: [SysFile, QbAsset, QbQuiz, QbQuizVersion, QbMaterial, QbAiUsage]
datasets:
  SysFile: urn:jabiz:dataset:platform:SysFile
  QbAsset: urn:jabiz:dataset:default:QbAsset
  QbQuiz: urn:jabiz:dataset:default:QbQuiz
  QbQuizVersion: urn:jabiz:dataset:default:QbQuizVersion
  QbMaterial: urn:jabiz:dataset:default:QbMaterial
  QbAiUsage: urn:jabiz:dataset:default:QbAiUsage
params:
  sponsorId: { like: QbAsset.sponsorId, required: true, description: The sponsor (their user id) }
  month:     { like: QbAiUsage.month, required: true, description: "The month of the snapshot's day (YYYY-MM, the deployment's zone)" }
results:
  meter: { kind: { type: text, maxLength: 32 } }
  value: { kind: { type: numeric, precision: 19, scale: 0 } }
permissions: [qb.usage.system]
---*/
-- Files: by uploader, the original's size (the platform keeps no size of the variants, A4-23). Assets: links to
-- outside videos count as assets, never as bytes. The text of an asset: its name, description, body, prize
-- description and the texts of its translations ({language: {field: {text, hash}}}), in UTF-8 bytes. Quizzes: not
-- removed; versions: all (append-only, kept for good); materials: all current ones (a removed quiz's are deleted with
-- it), the other languages' included. AI: the sponsor's row of the month (QbAiUsage holds the sponsors' jobs only;
-- the AI help keeps its own usage, so it is never counted here).
WITH f AS (
    SELECT count(*) AS n, coalesce(sum(f.{{SysFile.sizeBytes}}), 0) AS b
    FROM {{SysFile}} f
    WHERE f.{{SysFile.uploadedBy}} = :sponsorId),
a AS (
    SELECT count(*) AS n,
        coalesce(sum(
            coalesce(octet_length(a.{{QbAsset.name}}), 0)
            + coalesce(octet_length(a.{{QbAsset.description}}), 0)
            + coalesce(octet_length(a.{{QbAsset.body}}), 0)
            + coalesce(octet_length(a.{{QbAsset.prizeDescription}}), 0)
            + coalesce((SELECT sum(octet_length(t.value ->> 'text'))
                        FROM jsonb_each(CASE WHEN a.{{QbAsset.translations}} IS NULL THEN CAST('{}' AS jsonb)
                                             ELSE CAST(a.{{QbAsset.translations}} AS jsonb) END) l
                        CROSS JOIN LATERAL jsonb_each(CASE WHEN jsonb_typeof(l.value) = 'object' THEN l.value
                                                           ELSE CAST('{}' AS jsonb) END) t
                        WHERE jsonb_typeof(t.value) = 'object'), 0)), 0) AS b
    FROM {{QbAsset}} a
    WHERE a.{{QbAsset.sponsorId}} = :sponsorId),
q AS (
    SELECT count(*) AS n
    FROM {{QbQuiz}} q
    WHERE q.{{QbQuiz.ownerId}} = :sponsorId AND NOT q.{{QbQuiz.removed}}),
v AS (
    SELECT count(*) AS n
    FROM {{QbQuizVersion}} v
    WHERE v.{{QbQuizVersion.ownerId}} = :sponsorId),
m AS (
    SELECT count(*) AS n
    FROM {{QbMaterial}} m
    WHERE m.{{QbMaterial.ownerId}} = :sponsorId),
ai AS (
    SELECT coalesce(sum(u.{{QbAiUsage.inputTokens}} + u.{{QbAiUsage.outputTokens}}), 0) AS n
    FROM {{QbAiUsage}} u
    WHERE u.{{QbAiUsage.sponsorId}} = :sponsorId AND u.{{QbAiUsage.month}} = :month)
SELECT CAST('storage.bytes' AS varchar(32)) AS meter, CAST(f.b AS numeric(19, 0)) AS value FROM f
UNION ALL SELECT 'storage.files', f.n FROM f
UNION ALL SELECT 'assets.count', a.n FROM a
UNION ALL SELECT 'assets.text-bytes', a.b FROM a
UNION ALL SELECT 'content.quizzes', q.n FROM q
UNION ALL SELECT 'content.versions', v.n FROM v
UNION ALL SELECT 'content.materials', m.n FROM m
UNION ALL SELECT 'ai.tokens', ai.n FROM ai
