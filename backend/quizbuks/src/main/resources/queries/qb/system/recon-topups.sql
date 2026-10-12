/*---
id: qb.system.recon-topups
description: Top-ups whose postings do not match the record - the credit, the count of credits, what was taken back and the fee (the daily reconciliation; differences only)
entities: [QbTopUp, LedgerTransaction, LedgerEntry, LedgerAccount]
datasets:
  QbTopUp: urn:jabiz:dataset:default:QbTopUp
results:
  topUpId:   { from: QbTopUp.topUpId }
  sponsorId: { from: QbTopUp.sponsorId }
  checkCode: { kind: { type: text, maxLength: 30 } }
  expected:  { kind: { type: numeric, precision: 19, scale: 0 } }
  actual:    { kind: { type: numeric, precision: 19, scale: 0 } }
list:
  sorts: [topUpId, checkCode]
  defaultSort: { field: topUpId, asc: true }
  key: [topUpId, checkCode]
permissions: [qb.wallet.system]
---*/
-- One statement, one snapshot: a top-up's state and its postings are written in one transaction. The postings whose
-- source is a top-up, summed per top-up: the credit of 2110 (the top-up arriving: exactly one transaction, of the
-- amount, when paid; none otherwise), the debits of 2110 (refunds and lost disputes taken back) and the fee (5110).
SELECT
    t.{{QbTopUp.topUpId}}   AS topUpId,
    t.{{QbTopUp.sponsorId}} AS sponsorId,
    c.c_check               AS checkCode,
    c.c_expected            AS expected,
    c.c_actual              AS actual
FROM {{QbTopUp}} t
LEFT JOIN (
    SELECT tx.{{LedgerTransaction.sourceId}} AS s_topup,
           sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '2110' AND e.{{LedgerEntry.direction}} = 'CREDIT'
               THEN e.{{LedgerEntry.amount}} ELSE 0 END) AS s_credit,
           count(DISTINCT CASE WHEN a.{{LedgerAccount.accountCode}} = '2110'
               AND e.{{LedgerEntry.direction}} = 'CREDIT' THEN tx.{{LedgerTransaction.transactionId}} END) AS s_arrivals,
           sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '2110' AND e.{{LedgerEntry.direction}} = 'DEBIT'
               THEN e.{{LedgerEntry.amount}} ELSE 0 END) AS s_debit,
           sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '5110' THEN CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT'
               THEN e.{{LedgerEntry.amount}} ELSE -e.{{LedgerEntry.amount}} END ELSE 0 END) AS s_fee
    FROM {{LedgerTransaction}} tx
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = tx.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE tx.{{LedgerTransaction.sourceEntity}} = 'QbTopUp'
    GROUP BY tx.{{LedgerTransaction.sourceId}}) s ON s.s_topup = t.{{QbTopUp.topUpId}}
CROSS JOIN LATERAL (VALUES
    (CAST('TOPUP_LEDGER' AS varchar(30)),
        CAST(CASE WHEN t.{{QbTopUp.status}} = 'PAID' THEN t.{{QbTopUp.amount}} ELSE 0 END AS numeric),
        CAST(coalesce(s.s_credit, 0) AS numeric)),
    (CAST('TOPUP_ARRIVALS' AS varchar(30)),
        CAST(CASE WHEN t.{{QbTopUp.status}} = 'PAID' THEN 1 ELSE 0 END AS numeric),
        CAST(coalesce(s.s_arrivals, 0) AS numeric)),
    (CAST('TOPUP_REFUNDS' AS varchar(30)),
        CAST(t.{{QbTopUp.refundedAmount}} + t.{{QbTopUp.disputeClawback}} AS numeric),
        CAST(coalesce(s.s_debit, 0) AS numeric)),
    (CAST('TOPUP_FEE' AS varchar(30)),
        CAST(coalesce(t.{{QbTopUp.stripeFee}}, 0) AS numeric),
        CAST(coalesce(s.s_fee, 0) AS numeric))) AS c (c_check, c_expected, c_actual)
WHERE c.c_expected <> c.c_actual
