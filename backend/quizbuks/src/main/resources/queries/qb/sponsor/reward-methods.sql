/*---
id: qb.sponsor.reward-methods
description: >-
  The reward methods the sponsor may publish with now (Q28; F-S4b): the parameter qb.reward.default-methods, their own
  setting and the methods a node they lit opens (qb.tree.gate.digital-prize-node / offline-redeem-node); and the highest
  reward one attempt may win (qb.reward.max-per-attempt, none for 0)
entities: [QbSponsorRewardMethods, SysParam, QbTree, QbTreeNode, QbTreeLit, QbTreeLitRevocation]
datasets:
  QbSponsorRewardMethods: urn:jabiz:dataset:sponsor:QbSponsorRewardMethods
  SysParam: urn:jabiz:dataset:now:SysParam
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbTreeLit: urn:jabiz:dataset:me-sponsor:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:me-sponsor:QbTreeLitRevocation
results:
  methods:             { kind: { type: text, maxLength: 100 } }
  own:                 { kind: { type: bool } }
  unlocked:            { kind: { type: text, maxLength: 100 } }
  maxRewardPerAttempt: { kind: { type: monetary, currency: JPY, scale: 0 } }
  entryFeeEnabled:     { kind: { type: bool } }
permissions: [qb.publication.write]
---*/
-- One row, for the wizard's choice and the console's navigation. The rule is the processes' (publishing.RewardMethod
-- and PublicationEditing, which decide when a draft is saved or submitted): the parameter's names (before setup the
-- parameter is missing and the platform's Kudos alone apply, as the processes read it; a blank value too), with the
-- sponsor's own row of the setting and (F-S4b, the requester 2026-11-10) the methods a lit gate opens, in the order of
-- RewardMethod.ALL; names that are no method are dropped. A gate is open as tree.SponsorGates decides: its parameter
-- names a node in effect of a platform template Sponsors light, and the caller lit that node id in the template or in
-- an operator's copy of it (OPERATOR, templateTreeId), not revoked. The parameters are read at the server's clock (the
-- dataset without time travel). "unlocked" lists the methods a gate opens (the console's explanation).
WITH params AS (
    SELECT
        max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.reward.default-methods' THEN p.{{SysParam.value}} END) AS p_methods,
        max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.reward.max-per-attempt' THEN p.{{SysParam.value}} END) AS p_max,
        -- Q88b (D-Q88-14): whether the wizard offers the entry fee; the save refuses one when it is off.
        max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.play.entry-fee.enabled' THEN p.{{SysParam.value}} END) AS p_fee
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} IN ('qb.reward.default-methods', 'qb.reward.max-per-attempt',
        'qb.play.entry-fee.enabled')),
mine AS (
    SELECT max(m.{{QbSponsorRewardMethods.methods}}) AS methods, count(*) AS rows_found
    FROM {{QbSponsorRewardMethods}} m),
gates AS (
    SELECT g.{{SysParam.paramKey}} AS gate
    FROM {{SysParam}} g
    JOIN {{QbTree}} t ON CAST(t.{{QbTree.treeId}} AS text) = split_part(g.{{SysParam.value}}, '/', 1)
    JOIN {{QbTreeNode}} tn ON tn.{{QbTreeNode.treeId}} = split_part(g.{{SysParam.value}}, '/', 1)
        AND tn.{{QbTreeNode.nodeId}} = split_part(g.{{SysParam.value}}, '/', 2)
    WHERE g.{{SysParam.paramKey}} IN ('qb.tree.gate.digital-prize-node', 'qb.tree.gate.offline-redeem-node')
      AND g.{{SysParam.value}} ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      AND t.{{QbTree.ownerKind}} = 'PLATFORM' AND t.{{QbTree.subjectKind}} = 'SPONSOR'
      AND t.{{QbTree.status}} = 'ACTIVE' AND tn.{{QbTreeNode.active}}
      AND EXISTS (
          SELECT 1 FROM {{QbTreeLit}} l
          JOIN {{QbTree}} lt ON CAST(lt.{{QbTree.treeId}} AS text) = l.{{QbTreeLit.treeId}}
          WHERE l.{{QbTreeLit.subjectKind}} = 'SPONSOR' AND l.{{QbTreeLit.nodeId}} = tn.{{QbTreeNode.nodeId}}
            AND (CAST(lt.{{QbTree.treeId}} AS text) = tn.{{QbTreeNode.treeId}}
                 OR lt.{{QbTree.templateTreeId}} = tn.{{QbTreeNode.treeId}})
            AND NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                            WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text)))),
methods AS (
    SELECT a.m, a.ord,
           a.gate IS NOT NULL AND a.gate IN (SELECT gt.gate FROM gates gt) AS opened,
           a.m IN (SELECT btrim(x) FROM params, regexp_split_to_table(
                       CASE WHEN params.p_methods IS NULL OR btrim(params.p_methods) = '' THEN 'PLATFORM'
                            ELSE params.p_methods END, ',') AS x)
           OR a.m IN (SELECT btrim(y) FROM mine, regexp_split_to_table(
                       CASE WHEN mine.rows_found > 0 AND btrim(coalesce(mine.methods, '')) = '' THEN 'PLATFORM'
                            ELSE coalesce(mine.methods, '') END, ',') AS y) AS given
    FROM (VALUES ('PLATFORM', 1, CAST(NULL AS text)), ('SPONSOR_CASH', 2, CAST(NULL AS text)),
                 ('OFFLINE_REDEEM', 3, 'qb.tree.gate.offline-redeem-node'),
                 ('DIGITAL_PRIZE', 4, 'qb.tree.gate.digital-prize-node')) AS a(m, ord, gate))
SELECT
    coalesce((SELECT string_agg(x.m, ',' ORDER BY x.ord) FROM methods x WHERE x.given OR x.opened), '') AS methods,
    mine.rows_found > 0 AS own,
    coalesce((SELECT string_agg(x.m, ',' ORDER BY x.ord) FROM methods x WHERE x.opened), '') AS unlocked,
    CASE WHEN params.p_max ~ '^[0-9]{1,18}$' AND CAST(params.p_max AS numeric) > 0
        THEN CAST(params.p_max AS numeric) END AS maxRewardPerAttempt,
    coalesce(lower(btrim(params.p_fee)) = 'true', false) AS entryFeeEnabled
FROM mine CROSS JOIN params
