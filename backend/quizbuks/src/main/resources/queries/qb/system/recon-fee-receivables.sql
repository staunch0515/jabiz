/*---
id: qb.system.recon-fee-receivables
description: "Q87b: sponsors whose account 1130 differs from what their records say they owe (the daily reconciliation; differences only)"
entities: [QbFeeCharge, QbFeeInvoice, QbFeePayment, LedgerEntry, LedgerAccount]
datasets:
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
results:
  partyId:  { kind: { type: text, maxLength: 128 } }
  expected: { kind: { type: monetary, currency: JPY, scale: 0 } }
  actual:   { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [partyId]
  defaultSort: { field: partyId, asc: true }
  key: [partyId]
permissions: [qb.wallet.system]
---*/
-- Owed: what is due on the unpaid invoices, and of each month not invoiced yet its receivable charges less what was
-- paid early. Paid and voided invoices owe nothing.
SELECT coalesce(r.party, l.party) AS partyId, coalesce(l.balance, 0) AS expected, coalesce(r.owed, 0) AS actual
FROM (
    SELECT party, sum(owed) AS owed FROM (
        SELECT i.{{QbFeeInvoice.sponsorId}} AS party, i.{{QbFeeInvoice.due}} AS owed
        FROM {{QbFeeInvoice}} i WHERE i.{{QbFeeInvoice.status}} IN ('ISSUED', 'OVERDUE')
        UNION ALL
        SELECT c.{{QbFeeCharge.sponsorId}}, c.{{QbFeeCharge.amount}} + c.{{QbFeeCharge.tax}}
        FROM {{QbFeeCharge}} c
        WHERE c.{{QbFeeCharge.settlement}} = 'RECEIVABLE'
          AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i WHERE i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
              AND i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}})
        UNION ALL
        SELECT p.{{QbFeePayment.sponsorId}}, -p.{{QbFeePayment.amount}}
        FROM {{QbFeePayment}} p
        WHERE p.{{QbFeePayment.invoiceId}} IS NULL
          AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i WHERE i.{{QbFeeInvoice.sponsorId}} = p.{{QbFeePayment.sponsorId}}
              AND i.{{QbFeeInvoice.period}} = p.{{QbFeePayment.period}})) owed
    GROUP BY party) r
FULL OUTER JOIN (
    SELECT e.{{LedgerEntry.dimension1}} AS party,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS balance
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '1130'
    GROUP BY e.{{LedgerEntry.dimension1}}) l ON l.party = r.party
WHERE coalesce(l.balance, 0) <> coalesce(r.owed, 0)
