/*---
id: qb.system.operator-balance
description: "One operator's account (Q48e-2): what its settlements credited, what its withdrawals take (asked for, being paid, paid) and the balance; read by the withdrawal under the operator's lock"
entities: [QbOperatorSettlement, QbOperatorWithdrawal]
datasets:
  QbOperatorSettlement: urn:jabiz:dataset:default:QbOperatorSettlement
  QbOperatorWithdrawal: urn:jabiz:dataset:default:QbOperatorWithdrawal
params:
  operatorId: { like: QbOperatorSettlement.operatorId, required: true, description: The operator }
results:
  credited:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  withdrawn: { kind: { type: monetary, currency: JPY, scale: 0 } }
  balance:   { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.operator.settlement.system]
---*/
-- Always one row. Settlements issued before accounts existed (ISSUED, PAYING, PAID) are paid on their own and are
-- not in the account.
WITH credited AS (
    SELECT coalesce(sum(s.{{QbOperatorSettlement.payable}}), 0) AS amount
    FROM {{QbOperatorSettlement}} s
    WHERE s.{{QbOperatorSettlement.operatorId}} = :operatorId
      AND s.{{QbOperatorSettlement.status}} = 'CREDITED'
), withdrawn AS (
    SELECT coalesce(sum(w.{{QbOperatorWithdrawal.amount}}), 0) AS amount
    FROM {{QbOperatorWithdrawal}} w
    WHERE w.{{QbOperatorWithdrawal.operatorId}} = :operatorId
      AND w.{{QbOperatorWithdrawal.status}} IN ('REQUESTED', 'PAYING', 'PAID')
)
SELECT c.amount AS credited, w.amount AS withdrawn, c.amount - w.amount AS balance
FROM credited c CROSS JOIN withdrawn w
