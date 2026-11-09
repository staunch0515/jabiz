/*---
id: qb.sponsor.blockers
description: >-
  How many takers block the Sponsor (F-S8): those who blocked the Sponsor, one of its quizzes or publications, or it
  as a video provider, each once; none below the minimum group (qb.block.count-min-group), else rounded down to tens
  (never below the group).
  Never who, never what
entities: [QbSponsor, QbPublicationCard, QbBlock, SysParam]
datasets:
  QbSponsor: urn:jabiz:dataset:sponsor:QbSponsor
  QbPublicationCard: urn:jabiz:dataset:sponsor:QbPublicationCard
  QbBlock: urn:jabiz:dataset:default:QbBlock
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  blockers: { kind: { type: numeric, precision: 12, scale: 0 } }
  minGroup: { kind: { type: numeric, precision: 9, scale: 0 } }
permissions: [qb.sponsor.me]
---*/
-- The Sponsor's own row and cards (their datasets hold the caller's only). Blocks are private: the count is given only
-- from the minimum group up (the parameter if a whole number, else 20, never below 5) and rounded down to a multiple
-- of 10, never below the group (block.BlockRules.shownCount; qb.admin.blocked-sponsors counts the same), so that it does not point at a person.
WITH me AS (
    SELECT s.{{QbSponsor.userId}} AS id FROM {{QbSponsor}} s LIMIT 1),
params AS (
    SELECT greatest(5, coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,9}$'
                                         THEN CAST(p.{{SysParam.value}} AS int) END), 20)) AS k
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.block.count-min-group'),
mine AS (
    SELECT CAST(c.{{QbPublicationCard.publicationId}} AS text) AS publication, c.{{QbPublicationCard.quizId}} AS quiz
    FROM {{QbPublicationCard}} c),
counted AS (
    SELECT count(DISTINCT b.{{QbBlock.userId}}) AS n
    FROM {{QbBlock}} b
    CROSS JOIN me
    WHERE (b.{{QbBlock.kind}} IN ('SPONSOR', 'PROVIDER') AND b.{{QbBlock.objectId}} = me.id)
       OR (b.{{QbBlock.kind}} = 'PUBLICATION' AND b.{{QbBlock.objectId}} IN (SELECT publication FROM mine))
       OR (b.{{QbBlock.kind}} = 'QUIZ' AND b.{{QbBlock.objectId}} IN (SELECT quiz FROM mine WHERE quiz IS NOT NULL)))
SELECT
    CAST(CASE WHEN counted.n >= params.k THEN greatest(params.k, counted.n / 10 * 10) END AS numeric(12, 0))
        AS blockers,
    CAST(params.k AS numeric(9, 0)) AS minGroup
FROM counted CROSS JOIN params
