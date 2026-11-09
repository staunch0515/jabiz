/*---
id: qb.operator.fee-credit-onboarding
description: "F-M2: whether one sponsor of the caller's operator received the onboarding platform fee Kudos (another operator's sponsor: no rows) - when, how much, and whether the grant is switched on"
entities: [QbFeeCreditGrant, QbSponsorOperator, QbOperatorMember, SysParam]
datasets:
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  sponsorId: { like: QbFeeCreditGrant.sponsorId, required: true, description: "The sponsor (their user id)" }
results:
  enabled:   { kind: { type: bool } }
  grantedAt: { from: QbFeeCreditGrant.grantedAt }
  amount:    { from: QbFeeCreditGrant.amount }
  expiresAt: { from: QbFeeCreditGrant.expiresAt }
permissions: [qb.operator.use]
---*/
-- One row: the grant's switch (qb.fee-credit.grant.enabled; none: off) and the sponsor's onboarding lot, if any. The
-- confirmation (QB_FEE_CREDIT_ONBOARDING) checks everything again.
SELECT
    coalesce((SELECT p.{{SysParam.value}} = 'true' FROM {{SysParam}} p
              WHERE p.{{SysParam.paramKey}} = 'qb.fee-credit.grant.enabled'), false) AS enabled,
    g.{{QbFeeCreditGrant.grantedAt}} AS grantedAt,
    g.{{QbFeeCreditGrant.amount}}    AS amount,
    g.{{QbFeeCreditGrant.expiresAt}} AS expiresAt
FROM (SELECT 1 AS one) base
LEFT JOIN {{QbFeeCreditGrant}} g ON g.{{QbFeeCreditGrant.sponsorId}} = :sponsorId
    AND g.{{QbFeeCreditGrant.sourceKey}} = 'onboarding'
WHERE true
  AND EXISTS (SELECT 1 FROM {{QbSponsorOperator}} a
              JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
                AND me.{{QbOperatorMember.operatorId}} = a.{{QbSponsorOperator.operatorId}}
              WHERE a.{{QbSponsorOperator.userId}} = :sponsorId)
