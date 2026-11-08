/*---
id: qb.sponsor.benefit-catalog
description: >-
  Q89a (D-Q89-3): the benefits sponsors list, for a Sponsor to whom a benefit role is open (F-S2c: a gate node lit; none otherwise),
  newest first; with the provider's company and the caller's conversation about each
entities: [QbBenefitOffer, QbBenefitConversation, QbSponsor, SysParam, QbTree, QbTreeNode, QbTreeLit, QbTreeLitRevocation]
datasets:
  QbBenefitOffer: urn:jabiz:dataset:default:QbBenefitOffer
  QbBenefitConversation: urn:jabiz:dataset:sponsor:QbBenefitConversation
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  SysParam: urn:jabiz:dataset:now:SysParam
  QbTree: urn:jabiz:dataset:default:QbTree
  QbTreeNode: urn:jabiz:dataset:default:QbTreeNode
  QbTreeLit: urn:jabiz:dataset:me:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:me:QbTreeLitRevocation
params:
  kind:    { kind: { type: text, maxLength: 10 }, description: "Only benefits of this kind" }
  q:       { kind: { type: text, maxLength: 100 }, description: "Only benefits whose name or provider contains this text" }
  offerId: { kind: { type: text, maxLength: 36 }, description: "Only this benefit (its page)" }
results:
  offerId:         { from: QbBenefitOffer.offerId }
  providerCompany: { from: QbSponsor.company }
  kind:            { from: QbBenefitOffer.kind }
  name:            { from: QbBenefitOffer.name }
  description:     { from: QbBenefitOffer.description }
  image1:          { from: QbBenefitOffer.image1 }
  image2:          { from: QbBenefitOffer.image2 }
  image3:          { from: QbBenefitOffer.image3 }
  image4:          { from: QbBenefitOffer.image4 }
  unitValue:       { from: QbBenefitOffer.unitValue }
  pickupAddress:   { from: QbBenefitOffer.pickupAddress }
  pickupHours:     { from: QbBenefitOffer.pickupHours }
  terms:           { from: QbBenefitOffer.terms }
  editedAt:        { from: QbBenefitOffer.editedAt }
  conversationId:  { from: QbBenefitConversation.conversationId }
list:
  filters: [kind]
  sorts:   [editedAt, name, unitValue]
  defaultSort: { field: editedAt, asc: false }
  key: [offerId]
permissions: [qb.benefit.use]
---*/
-- Only the listed benefits (the caller's own too: buying one's own is refused by the processes), and only while either
-- benefit role is open to the caller.
-- A provider whose onboarding is gone (an erased account) is not shown. The text is found as written: strpos takes
-- it literally.
WITH held AS (
    -- F-S2c: either benefit role open to the caller (tree.TreeGates's rule, as qb.sponsor.benefit-access).
    SELECT 1 AS yes
    FROM (VALUES ('qb.tree.gate.benefit-provider-node'), ('qb.tree.gate.benefit-sponsor-node')) AS g(param_key)
    JOIN {{SysParam}} p
        ON p.{{SysParam.paramKey}} = g.param_key
       AND p.{{SysParam.value}} ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    JOIN {{QbTree}} t
        ON CAST(t.{{QbTree.treeId}} AS text) = split_part(p.{{SysParam.value}}, '/', 1)
       AND t.{{QbTree.ownerKind}} = 'PLATFORM' AND t.{{QbTree.subjectKind}} = 'TAKER' AND t.{{QbTree.status}} = 'ACTIVE'
    JOIN {{QbTreeNode}} tn
        ON tn.{{QbTreeNode.treeId}} = split_part(p.{{SysParam.value}}, '/', 1)
       AND tn.{{QbTreeNode.nodeId}} = split_part(p.{{SysParam.value}}, '/', 2) AND tn.{{QbTreeNode.active}}
    WHERE EXISTS (
        SELECT 1 FROM {{QbTreeLit}} tl
        WHERE tl.{{QbTreeLit.subjectKind}} = 'USER'
          AND tl.{{QbTreeLit.treeId}} = tn.{{QbTreeNode.treeId}} AND tl.{{QbTreeLit.nodeId}} = tn.{{QbTreeNode.nodeId}}
          AND NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} tr
                          WHERE tr.{{QbTreeLitRevocation.litId}} = CAST(tl.{{QbTreeLit.litId}} AS text)))
    LIMIT 1
)
SELECT
    o.{{QbBenefitOffer.offerId}}       AS offerId,
    sp.{{QbSponsor.company}}           AS providerCompany,
    o.{{QbBenefitOffer.kind}}          AS kind,
    o.{{QbBenefitOffer.name}}          AS name,
    o.{{QbBenefitOffer.description}}   AS description,
    o.{{QbBenefitOffer.image1}}        AS image1,
    o.{{QbBenefitOffer.image2}}        AS image2,
    o.{{QbBenefitOffer.image3}}        AS image3,
    o.{{QbBenefitOffer.image4}}        AS image4,
    o.{{QbBenefitOffer.unitValue}}     AS unitValue,
    o.{{QbBenefitOffer.pickupAddress}} AS pickupAddress,
    o.{{QbBenefitOffer.pickupHours}}   AS pickupHours,
    o.{{QbBenefitOffer.terms}}         AS terms,
    o.{{QbBenefitOffer.editedAt}}      AS editedAt,
    c.{{QbBenefitConversation.conversationId}} AS conversationId
FROM {{QbBenefitOffer}} o
JOIN held ON true
JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = o.{{QbBenefitOffer.providerId}}
LEFT JOIN {{QbBenefitConversation}} c ON c.{{QbBenefitConversation.offerId}} = o.{{QbBenefitOffer.offerId}}
WHERE o.{{QbBenefitOffer.status}} = 'LISTED'
  AND (CAST(:kind AS text) IS NULL OR o.{{QbBenefitOffer.kind}} = CAST(:kind AS text))
  AND (CAST(:offerId AS text) IS NULL OR o.{{QbBenefitOffer.offerId}} = CAST(:offerId AS text))
  AND (CAST(:q AS text) IS NULL OR strpos(lower(o.{{QbBenefitOffer.name}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(sp.{{QbSponsor.company}}, '')), lower(CAST(:q AS text))) > 0)
