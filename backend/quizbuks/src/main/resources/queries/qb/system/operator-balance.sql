/*---
id: qb.system.operator-balance
description: "One operator's account (Q48e-2): what its settlements credited, what its withdrawals take (asked for, being paid, paid), what it moved to its direct Sponsor (F-M4) and the balance; read by the withdrawal and the transfer under the operator's lock"
entities: [QbOperatorSettlement, QbOperatorWithdrawal, QbOperatorTransfer]
datasets:
  QbOperatorSettlement: urn:jabiz:dataset:default:QbOperatorSettlement
  QbOperatorWithdrawal: urn:jabiz:dataset:default:QbOperatorWithdrawal
  QbOperatorTransfer: urn:jabiz:dataset:default:QbOperatorTransfer
params:
  operatorId: { like: QbOperatorSettlement.operatorId, required: true, description: The operator }
results:
  credited:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  withdrawn: { kind: { type: monetary, currency: JPY, scale: 0 } }
  transferred: { kind: { type: monetary, currency: JPY, scale: 0 } }
  balance:   { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.operator.settlement.system]
---*/
-- Always one row. Settlements issued before accounts existed (ISSUED, PAYING, PAID) are paid on their own and are
-- not in the account.
WITH credited AS (
    SELECT coalesce(sum(s.{{QbOperatorSettlement.payable}}), 0) AS r_amount
    FROM {{QbOperatorSettlement}} s
    WHERE s.{{QbOperatorSettlement.operatorId}} = :operatorId
      AND s.{{QbOperatorSettlement.status}} = 'CREDITED'
), withdrawn AS (
    SELECT coalesce(sum(w.{{QbOperatorWithdrawal.amount}}), 0) AS r_amount
    FROM {{QbOperatorWithdrawal}} w
    WHERE w.{{QbOperatorWithdrawal.operatorId}} = :operatorId
      AND w.{{QbOperatorWithdrawal.status}} IN ('REQUESTED', 'PAYING', 'PAID')
), transferred AS (
    SELECT coalesce(sum(t.{{QbOperatorTransfer.amount}}), 0) AS r_amount
    FROM {{QbOperatorTransfer}} t
    WHERE t.{{QbOperatorTransfer.operatorId}} = :operatorId
)
SELECT c.r_amount AS credited, w.r_amount AS withdrawn, t.r_amount AS transferred,
       c.r_amount - w.r_amount - t.r_amount AS balance
FROM credited c CROSS JOIN withdrawn w CROSS JOIN transferred t
