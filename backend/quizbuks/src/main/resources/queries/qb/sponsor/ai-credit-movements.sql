/*---
id: qb.sponsor.ai-credit-movements
description: "What went into and out of the Sponsor's AI credit (F-M9b), newest first: purchases, the month's charges, returns and expired granted credit, each with the granted credit and the cash in it and what was left after"
entities: [QbAiCreditMovement]
datasets:
  QbAiCreditMovement: urn:jabiz:dataset:sponsor:QbAiCreditMovement
results:
  movementId: { from: QbAiCreditMovement.movementId }
  kind:       { from: QbAiCreditMovement.kind }
  amount:     { from: QbAiCreditMovement.amount }
  credit:     { from: QbAiCreditMovement.credit }
  cash:       { from: QbAiCreditMovement.cash }
  balance:    { from: QbAiCreditMovement.balance }
  movedAt:    { from: QbAiCreditMovement.movedAt }
list:
  sorts: [movedAt]
  defaultSort: { field: movedAt, asc: false }
  key: [movementId]
permissions: [qb.sponsor.finance.read]
---*/
-- The caller's own movements (the dataset's scope), through qb_ai_credit_movement_version_sponsor_idx; the reference
-- (a request key, a charge) is not shown.
SELECT m.{{QbAiCreditMovement.movementId}} AS movementId, m.{{QbAiCreditMovement.kind}} AS kind,
       m.{{QbAiCreditMovement.amount}} AS amount, m.{{QbAiCreditMovement.credit}} AS credit,
       m.{{QbAiCreditMovement.cash}} AS cash, m.{{QbAiCreditMovement.balance}} AS balance,
       m.{{QbAiCreditMovement.movedAt}} AS movedAt
FROM {{QbAiCreditMovement}} m
