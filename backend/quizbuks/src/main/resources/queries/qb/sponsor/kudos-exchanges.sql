/*---
id: qb.sponsor.kudos-exchanges
description: The sponsor's own exchanges of cash and Kudos (Q87), newest first
entities: [QbKudosExchange]
datasets:
  QbKudosExchange: urn:jabiz:dataset:sponsor:QbKudosExchange
results:
  exchangeId:  { from: QbKudosExchange.exchangeId }
  direction:   { from: QbKudosExchange.direction }
  amount:      { from: QbKudosExchange.amount }
  exchangedAt: { from: QbKudosExchange.exchangedAt }
list:
  filters: [direction, exchangedAt]
  sorts:   [exchangedAt, amount]
  defaultSort: { field: exchangedAt, asc: false }
  key: [exchangeId]
permissions: [qb.sponsor.finance.read]
---*/
SELECT
    x.{{QbKudosExchange.exchangeId}}  AS exchangeId,
    x.{{QbKudosExchange.direction}}   AS direction,
    x.{{QbKudosExchange.amount}}      AS amount,
    x.{{QbKudosExchange.exchangedAt}} AS exchangedAt
FROM {{QbKudosExchange}} x
