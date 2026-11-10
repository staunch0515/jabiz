/*---
id: qb.admin.fee-credit-summary
description: The platform fee Kudos granted (F-M2), for headquarters, read only, summed by operator (when granted), by sponsor or by source - how many lots, granted, spent, left and voided
entities: [QbFeeCreditGrant, QbSponsor, QbOperator]
datasets:
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbOperator: urn:jabiz:dataset:default:QbOperator
params:
  groupBy: { kind: { type: text, maxLength: 10 }, required: true, description: "OPERATOR, SPONSOR or SOURCE" }
results:
  groupKey:  { kind: { type: text, maxLength: 128 } }
  label:     { kind: { type: text, maxLength: 200 } }
  lots:      { kind: { type: numeric, precision: 12, scale: 0 } }
  granted:   { kind: { type: monetary, currency: JPY, scale: 0 } }
  spent:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  remaining: { kind: { type: monetary, currency: JPY, scale: 0 } }
  voided:    { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts:   [granted, groupKey]
  defaultSort: { field: granted, asc: false }
  key: [groupKey]
permissions: [qb.admin.finance.read]
---*/
-- A lot granted with no operator recorded is summed under '-' (none: the sponsor had no operator then). label is the
-- operator's name, the sponsor's company, or the source's code (the page names it from the dictionary).
SELECT
    CAST(x.x_k AS varchar(128))                 AS groupKey,
    CAST(max(x.x_label) AS varchar(200))        AS label,
    count(*)                                  AS lots,
    sum(x.x_amount)                           AS granted,
    sum(x.x_amount - x.x_left - x.x_voided)   AS spent,
    sum(x.x_left)                             AS remaining,
    sum(x.x_voided)                           AS voided
FROM (
    SELECT CASE :groupBy WHEN 'OPERATOR' THEN coalesce(g.{{QbFeeCreditGrant.operatorId}}, '-')
                         WHEN 'SPONSOR' THEN g.{{QbFeeCreditGrant.sponsorId}}
                         ELSE g.{{QbFeeCreditGrant.source}} END AS x_k,
           CASE :groupBy WHEN 'OPERATOR' THEN o.{{QbOperator.name}}
                         WHEN 'SPONSOR' THEN s.{{QbSponsor.company}}
                         ELSE g.{{QbFeeCreditGrant.source}} END AS x_label,
           g.{{QbFeeCreditGrant.amount}} AS x_amount, g.{{QbFeeCreditGrant.remaining}} AS x_left,
           g.{{QbFeeCreditGrant.voidedAmount}} AS x_voided
    FROM {{QbFeeCreditGrant}} g
    LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = g.{{QbFeeCreditGrant.sponsorId}}
    LEFT JOIN {{QbOperator}} o ON CAST(o.{{QbOperator.operatorId}} AS text) = g.{{QbFeeCreditGrant.operatorId}}
    WHERE :groupBy IN ('OPERATOR', 'SPONSOR', 'SOURCE')
) x
GROUP BY x.x_k
