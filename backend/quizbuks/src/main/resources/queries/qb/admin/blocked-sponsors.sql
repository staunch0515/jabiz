/*---
id: qb.admin.blocked-sponsors
description: >-
  The Sponsors most blocked (F-S8), for headquarters: each Sponsor with at least the minimum group of takers blocking
  it (qb.block.count-min-group), the count rounded down to tens, as the Sponsor itself is shown it. Never who
entities: [QbBlock, QbPublicationCard, QbSponsor, SysParam]
datasets:
  QbBlock: urn:jabiz:dataset:default:QbBlock
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  sponsorId: { from: QbSponsor.userId }
  company:   { from: QbSponsor.company }
  blockers:  { kind: { type: numeric, precision: 12, scale: 0 } }
  minGroup:  { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [blockers]
  defaultSort: { field: blockers, asc: false }
  key: [sponsorId]
permissions: [qb.admin.block.read]
---*/
-- The count of qb.sponsor.blockers for every Sponsor at once: the takers who blocked the Sponsor, it as a video
-- provider, one of its quizzes or publications (by the cards), each once; below the minimum group not listed.
WITH params AS (
    SELECT greatest(5, coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,9}$'
                                         THEN CAST(p.{{SysParam.value}} AS int) END), 20)) AS k
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.block.count-min-group'),
hits AS (
    SELECT b.{{QbBlock.userId}} AS taker, b.{{QbBlock.objectId}} AS sponsor
    FROM {{QbBlock}} b
    WHERE b.{{QbBlock.kind}} IN ('SPONSOR', 'PROVIDER')
    UNION
    SELECT b.{{QbBlock.userId}}, c.{{QbPublicationCard.publisherId}}
    FROM {{QbBlock}} b
    JOIN {{QbPublicationCard}} c ON CAST(c.{{QbPublicationCard.publicationId}} AS text) = b.{{QbBlock.objectId}}
    WHERE b.{{QbBlock.kind}} = 'PUBLICATION'
    UNION
    SELECT b.{{QbBlock.userId}}, c.{{QbPublicationCard.publisherId}}
    FROM {{QbBlock}} b
    JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.quizId}} = b.{{QbBlock.objectId}}
    WHERE b.{{QbBlock.kind}} = 'QUIZ'),
counted AS (
    SELECT hits.sponsor, count(DISTINCT hits.taker) AS n FROM hits GROUP BY hits.sponsor)
SELECT
    sp.{{QbSponsor.userId}}                                  AS sponsorId,
    sp.{{QbSponsor.company}}                                 AS company,
    CAST(greatest(params.k, counted.n / 10 * 10) AS numeric(12, 0)) AS blockers,
    CAST(params.k AS numeric(9, 0))                          AS minGroup
FROM counted
CROSS JOIN params
JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = counted.sponsor
WHERE counted.n >= params.k
