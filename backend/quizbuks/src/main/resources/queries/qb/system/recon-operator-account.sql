/*---
id: qb.system.recon-operator-account
description: Whether an operator's account (2140) agrees with its settlements, withdrawals and transfers, by operator (F-M4; sponsorId holds the operator) (one of the four templates of the daily fee-account reconciliation, F-M14 G10)
entities: [LedgerEntry, LedgerAccount, LedgerTransaction, QbOperatorSettlement, QbOperatorWithdrawal, QbOperatorTransfer]
datasets:
  QbOperatorSettlement: urn:jabiz:dataset:default:QbOperatorSettlement
  QbOperatorWithdrawal: urn:jabiz:dataset:default:QbOperatorWithdrawal
  QbOperatorTransfer: urn:jabiz:dataset:default:QbOperatorTransfer
results:
  checkCode: { kind: { type: text, maxLength: 30 } }
  sponsorId: { kind: { type: text, maxLength: 128 } }
  reference: { kind: { type: text, maxLength: 128 } }
  expected:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  actual:    { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [checkCode, sponsorId]
  defaultSort: { field: checkCode, asc: true }
  key: [checkCode, sponsorId, reference]
permissions: [qb.wallet.system]
---*/
-- OPERATOR_ACCOUNT (F-M4): for an operator (sponsorId holds the operator), 2140 posted from its settlements,
--   withdrawals and transfers (credit less debit, each connected to the operator by its source) against the records:
--   every settlement's payable less those paid on their own (PAID, before accounts existed), the withdrawals paid and
--   the transfers to its direct Sponsor.
WITH operator_ledger AS (
    SELECT coalesce(st.{{QbOperatorSettlement.operatorId}}, wd.{{QbOperatorWithdrawal.operatorId}},
                    tr.{{QbOperatorTransfer.operatorId}}, '-') AS r_operator,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_balance
    FROM {{LedgerTransaction}} t
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    LEFT JOIN {{QbOperatorSettlement}} st ON t.{{LedgerTransaction.sourceEntity}} = 'QbOperatorSettlement'
        AND CAST(st.{{QbOperatorSettlement.settlementId}} AS text) = t.{{LedgerTransaction.sourceId}}
    LEFT JOIN {{QbOperatorWithdrawal}} wd ON t.{{LedgerTransaction.sourceEntity}} = 'QbOperatorWithdrawal'
        AND CAST(wd.{{QbOperatorWithdrawal.withdrawalId}} AS text) = t.{{LedgerTransaction.sourceId}}
    LEFT JOIN {{QbOperatorTransfer}} tr ON t.{{LedgerTransaction.sourceEntity}} = 'QbOperatorTransfer'
        AND CAST(tr.{{QbOperatorTransfer.transferId}} AS text) = t.{{LedgerTransaction.sourceId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2140'
    GROUP BY 1
),
operator_records AS (
    SELECT r.r_operator, sum(r.r_amount) AS r_balance
    FROM (
        SELECT s.{{QbOperatorSettlement.operatorId}} AS r_operator,
               s.{{QbOperatorSettlement.payable}}
               - CASE WHEN s.{{QbOperatorSettlement.status}} = 'PAID' THEN s.{{QbOperatorSettlement.payable}}
                      ELSE 0 END AS r_amount
        FROM {{QbOperatorSettlement}} s
        UNION ALL
        SELECT w.{{QbOperatorWithdrawal.operatorId}}, -w.{{QbOperatorWithdrawal.amount}}
        FROM {{QbOperatorWithdrawal}} w
        WHERE w.{{QbOperatorWithdrawal.status}} = 'PAID'
        UNION ALL
        SELECT x.{{QbOperatorTransfer.operatorId}}, -x.{{QbOperatorTransfer.amount}}
        FROM {{QbOperatorTransfer}} x) r
    GROUP BY r.r_operator
)
SELECT CAST('OPERATOR_ACCOUNT' AS varchar(30)) AS checkCode,
       CAST(coalesce(orc.r_operator, ol.r_operator) AS varchar(128)) AS sponsorId, CAST(NULL AS varchar(128)) AS reference,
       coalesce(orc.r_balance, 0) AS expected, coalesce(ol.r_balance, 0) AS actual
FROM operator_records orc
FULL OUTER JOIN operator_ledger ol ON ol.r_operator = orc.r_operator
WHERE coalesce(orc.r_balance, 0) <> coalesce(ol.r_balance, 0)
