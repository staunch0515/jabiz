/*---
id: qb.system.operator-revenue
description: "The commission (4110, credits less debits) of one operator's publications booked in a period (Q48b): the publications whose card names the operator, their sponsor's operator when they went on"
entities: [LedgerAccount, LedgerTransaction, LedgerEntry, QbPublicationCard]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
params:
  operatorId: { like: QbPublicationCard.operatorId, required: true, description: The operator }
  from:       { like: LedgerTransaction.bookingTime, required: true, description: "The period's first instant" }
  to:         { like: LedgerTransaction.bookingTime, required: true, description: "The next period's first instant" }
results:
  revenue: { kind: { type: monetary, currency: JPY, scale: 0 } }
  lines:   { kind: { type: numeric, precision: 12, scale: 0 } }
permissions: [qb.operator.settlement.system]
---*/
-- Always one row. The reward lines carry their publication (dimension 2, the publication's id as text); a reversed
-- reward's debit takes its commission back in the month it is booked. Transfer fees (4110 without a publication) are
-- headquarters' (D-Q48-6).
SELECT coalesce(sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
                         ELSE -e.{{LedgerEntry.amount}} END), 0) AS revenue,
       count(e.{{LedgerEntry.entryId}}) AS lines
FROM {{QbPublicationCard}} c
JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.dimension2}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)
JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
WHERE c.{{QbPublicationCard.operatorId}} = :operatorId
  AND a.{{LedgerAccount.accountCode}} = '4110'
  AND t.{{LedgerTransaction.bookingTime}} >= :from
  AND t.{{LedgerTransaction.bookingTime}} < :to
