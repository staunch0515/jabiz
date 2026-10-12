/*---
id: qb.system.recon-transfers
description: Transfers whose postings do not match their state - what left the taker's balance and what is still in transit (the daily reconciliation; differences only)
entities: [QbTransfer, LedgerTransaction, LedgerEntry, LedgerAccount]
datasets:
  QbTransfer: urn:jabiz:dataset:default:QbTransfer
results:
  transferId: { from: QbTransfer.transferId }
  userId:     { from: QbTransfer.userId }
  checkCode:  { kind: { type: text, maxLength: 30 } }
  expected:   { kind: { type: numeric, precision: 19, scale: 0 } }
  actual:     { kind: { type: numeric, precision: 19, scale: 0 } }
list:
  sorts: [transferId, checkCode]
  defaultSort: { field: transferId, asc: true }
  key: [transferId, checkCode]
permissions: [qb.payout.system]
---*/
-- One statement, one snapshot: a transfer's state and its postings are written in one transaction. The postings whose
-- source is a transfer, summed per transfer. TRANSFER_LEDGER: what left account 2120 (debit less credit: the sending,
-- less its reversal when it failed, less what Stripe reversed) is the amount once sent (in transit, settled or
-- reversed), less what was reversed; 0 in review, rejected or failed. TRANSFER_IN_TRANSIT: account 2130 for the
-- transfer (credit less debit) is the net while sending or in doubt, 0 otherwise (settled, failed, never sent).
SELECT
    t.{{QbTransfer.transferId}} AS transferId,
    t.{{QbTransfer.userId}}     AS userId,
    c.c_check                   AS checkCode,
    c.c_expected                AS expected,
    c.c_actual                  AS actual
FROM {{QbTransfer}} t
LEFT JOIN (
    SELECT tx.{{LedgerTransaction.sourceId}} AS s_transfer,
           sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '2120' THEN CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT'
               THEN e.{{LedgerEntry.amount}} ELSE -e.{{LedgerEntry.amount}} END ELSE 0 END) AS s_out,
           sum(CASE WHEN a.{{LedgerAccount.accountCode}} = '2130' THEN CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT'
               THEN e.{{LedgerEntry.amount}} ELSE -e.{{LedgerEntry.amount}} END ELSE 0 END) AS s_transit
    FROM {{LedgerTransaction}} tx
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = tx.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE tx.{{LedgerTransaction.sourceEntity}} = 'QbTransfer'
    GROUP BY tx.{{LedgerTransaction.sourceId}}) s ON s.s_transfer = t.{{QbTransfer.transferId}}
CROSS JOIN LATERAL (VALUES
    (CAST('TRANSFER_LEDGER' AS varchar(30)),
        CAST(CASE WHEN t.{{QbTransfer.status}} IN ('SENDING', 'CHECK', 'SENT', 'REVERSED')
            THEN t.{{QbTransfer.amount}} - t.{{QbTransfer.reversedAmount}} ELSE 0 END AS numeric),
        CAST(coalesce(s.s_out, 0) AS numeric)),
    (CAST('TRANSFER_IN_TRANSIT' AS varchar(30)),
        CAST(CASE WHEN t.{{QbTransfer.status}} IN ('SENDING', 'CHECK') THEN t.{{QbTransfer.net}} ELSE 0 END AS numeric),
        CAST(coalesce(s.s_transit, 0) AS numeric))) AS c (c_check, c_expected, c_actual)
WHERE c.c_expected <> c.c_actual
