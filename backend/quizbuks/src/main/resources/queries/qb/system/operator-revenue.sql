/*---
id: qb.system.operator-revenue
description: "The commission (4110, credits less debits) of one operator's publications booked in a period (Q48b): the publications whose card names the operator, their sponsor's operator when they went on; and (Q87b) the platform fees (4120) charged under the operator"
entities: [LedgerAccount, LedgerTransaction, LedgerEntry, QbPublicationCard, QbFeeCharge]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
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
-- headquarters' (D-Q48-6). Q87b (D-Q87-8): the platform fees before tax (4120) of the charges made while the sponsor
-- was the operator's (the charge keeps its operator), a voided invoice's reversals taking them back in their month.
SELECT coalesce(sum(x.signed), 0) AS revenue, count(*) AS lines
FROM (
    SELECT CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
                ELSE -e.{{LedgerEntry.amount}} END AS signed
    FROM {{QbPublicationCard}} c
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.dimension2}} = CAST(c.{{QbPublicationCard.publicationId}} AS text)
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    WHERE c.{{QbPublicationCard.operatorId}} = :operatorId
      AND a.{{LedgerAccount.accountCode}} = '4110'
      AND t.{{LedgerTransaction.bookingTime}} >= :from
      AND t.{{LedgerTransaction.bookingTime}} < :to
    UNION ALL
    SELECT CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
                ELSE -e.{{LedgerEntry.amount}} END
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    LEFT JOIN {{LedgerTransaction}} o ON o.{{LedgerTransaction.transactionId}} = t.{{LedgerTransaction.reversesTransactionId}}
    JOIN {{QbFeeCharge}} f ON coalesce(o.{{LedgerTransaction.sourceEntity}}, t.{{LedgerTransaction.sourceEntity}}) = 'QbFeeCharge'
        AND CAST(f.{{QbFeeCharge.chargeId}} AS text) = coalesce(o.{{LedgerTransaction.sourceId}}, t.{{LedgerTransaction.sourceId}})
    WHERE f.{{QbFeeCharge.operatorId}} = CAST(:operatorId AS text)
      AND a.{{LedgerAccount.accountCode}} = '4120'
      AND t.{{LedgerTransaction.bookingTime}} >= :from
      AND t.{{LedgerTransaction.bookingTime}} < :to) x
