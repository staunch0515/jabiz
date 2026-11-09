/*---
id: qb.admin.fee-credit-grants
description: Every lot of platform fee Kudos granted (F-M2), for headquarters, read only - the sponsor, their operator when it was granted, where it came from, the amount, what was spent, what is left and what expired unused
entities: [QbFeeCreditGrant, QbSponsor, QbOperator]
datasets:
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbOperator: urn:jabiz:dataset:default:QbOperator
results:
  grantId:      { from: QbFeeCreditGrant.grantId }
  sponsorId:    { from: QbFeeCreditGrant.sponsorId }
  company:      { from: QbSponsor.company }
  operatorId:   { from: QbFeeCreditGrant.operatorId }
  operatorName: { from: QbOperator.name }
  source:       { from: QbFeeCreditGrant.source }
  amount:       { from: QbFeeCreditGrant.amount }
  spent:        { from: QbFeeCreditGrant.amount }
  remaining:    { from: QbFeeCreditGrant.remaining }
  voidedAmount: { from: QbFeeCreditGrant.voidedAmount }
  grantedAt:    { from: QbFeeCreditGrant.grantedAt }
  expiresAt:    { from: QbFeeCreditGrant.expiresAt }
  voidedAt:     { from: QbFeeCreditGrant.voidedAt }
list:
  filters: [sponsorId, operatorId, source, grantedAt, expiresAt]
  sorts:   [grantedAt, expiresAt, amount]
  defaultSort: { field: grantedAt, asc: false }
  key: [grantId]
permissions: [qb.admin.finance.read]
---*/
-- spent is what payments took of it (the amount less what is left and what was voided).
SELECT
    g.{{QbFeeCreditGrant.grantId}}      AS grantId,
    g.{{QbFeeCreditGrant.sponsorId}}    AS sponsorId,
    s.{{QbSponsor.company}}             AS company,
    g.{{QbFeeCreditGrant.operatorId}}   AS operatorId,
    o.{{QbOperator.name}}               AS operatorName,
    g.{{QbFeeCreditGrant.source}}       AS source,
    g.{{QbFeeCreditGrant.amount}}       AS amount,
    g.{{QbFeeCreditGrant.amount}} - g.{{QbFeeCreditGrant.remaining}} - g.{{QbFeeCreditGrant.voidedAmount}} AS spent,
    g.{{QbFeeCreditGrant.remaining}}    AS remaining,
    g.{{QbFeeCreditGrant.voidedAmount}} AS voidedAmount,
    g.{{QbFeeCreditGrant.grantedAt}}    AS grantedAt,
    g.{{QbFeeCreditGrant.expiresAt}}    AS expiresAt,
    g.{{QbFeeCreditGrant.voidedAt}}     AS voidedAt
FROM {{QbFeeCreditGrant}} g
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = g.{{QbFeeCreditGrant.sponsorId}}
LEFT JOIN {{QbOperator}} o ON CAST(o.{{QbOperator.operatorId}} AS text) = g.{{QbFeeCreditGrant.operatorId}}
