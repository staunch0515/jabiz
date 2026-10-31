/*---
id: qb.operator.account
description: "The caller operator's account (Q48e-2): the balance, what its settlements credited and its withdrawals took, the least it may withdraw, and its bank account (the number masked) with when withdrawals to it are possible"
entities: [QbOperatorMember, QbOperatorSettlement, QbOperatorWithdrawal, QbOperatorBankAccount, SysParam]
datasets:
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  QbOperatorSettlement: urn:jabiz:dataset:default:QbOperatorSettlement
  QbOperatorWithdrawal: urn:jabiz:dataset:default:QbOperatorWithdrawal
  QbOperatorBankAccount: urn:jabiz:dataset:default:QbOperatorBankAccount
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  operatorId:    { from: QbOperatorMember.operatorId }
  lead:          { from: QbOperatorMember.lead }
  credited:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  withdrawn:     { kind: { type: monetary, currency: JPY, scale: 0 } }
  balance:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  minimum:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  cooldownHours: { kind: { type: numeric, precision: 4, scale: 0 } }
  bankName:      { from: QbOperatorBankAccount.bankName }
  branchName:    { from: QbOperatorBankAccount.branchName }
  accountType:   { from: QbOperatorBankAccount.accountType }
  accountNumber: { from: QbOperatorBankAccount.accountNumber }
  accountHolder: { from: QbOperatorBankAccount.accountHolder }
  changedAt:     { from: QbOperatorBankAccount.changedAt }
permissions: [qb.operator.use]
---*/
-- One row for a member (none otherwise): the caller's own, current membership names the operator. The balance is
-- qb.system.operator-balance's; the withdrawal decides under the operator's lock.
WITH params AS (
    SELECT p.{{SysParam.paramKey}} AS k, NULLIF(p.{{SysParam.value}}, '') AS v
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} IN ('qb.operator.withdrawal.min', 'qb.operator.withdrawal.cooldown-hours')
)
SELECT
    me.{{QbOperatorMember.operatorId}} AS operatorId,
    me.{{QbOperatorMember.lead}} AS lead,
    (SELECT coalesce(sum(s.{{QbOperatorSettlement.payable}}), 0) FROM {{QbOperatorSettlement}} s
     WHERE s.{{QbOperatorSettlement.operatorId}} = me.{{QbOperatorMember.operatorId}}
       AND s.{{QbOperatorSettlement.status}} = 'CREDITED') AS credited,
    (SELECT coalesce(sum(w.{{QbOperatorWithdrawal.amount}}), 0) FROM {{QbOperatorWithdrawal}} w
     WHERE w.{{QbOperatorWithdrawal.operatorId}} = me.{{QbOperatorMember.operatorId}}
       AND w.{{QbOperatorWithdrawal.status}} IN ('REQUESTED', 'PAYING', 'PAID')) AS withdrawn,
    (SELECT coalesce(sum(s.{{QbOperatorSettlement.payable}}), 0) FROM {{QbOperatorSettlement}} s
     WHERE s.{{QbOperatorSettlement.operatorId}} = me.{{QbOperatorMember.operatorId}}
       AND s.{{QbOperatorSettlement.status}} = 'CREDITED')
    - (SELECT coalesce(sum(w.{{QbOperatorWithdrawal.amount}}), 0) FROM {{QbOperatorWithdrawal}} w
     WHERE w.{{QbOperatorWithdrawal.operatorId}} = me.{{QbOperatorMember.operatorId}}
       AND w.{{QbOperatorWithdrawal.status}} IN ('REQUESTED', 'PAYING', 'PAID')) AS balance,
    CAST((SELECT v FROM params WHERE k = 'qb.operator.withdrawal.min') AS numeric(18, 0)) AS minimum,
    CAST((SELECT v FROM params WHERE k = 'qb.operator.withdrawal.cooldown-hours') AS numeric(4, 0)) AS cooldownHours,
    b.{{QbOperatorBankAccount.bankName}} AS bankName,
    b.{{QbOperatorBankAccount.branchName}} AS branchName,
    b.{{QbOperatorBankAccount.accountType}} AS accountType,
    b.{{QbOperatorBankAccount.accountNumber}} AS accountNumber,
    b.{{QbOperatorBankAccount.accountHolder}} AS accountHolder,
    b.{{QbOperatorBankAccount.changedAt}} AS changedAt
FROM {{QbOperatorMember}} me
LEFT JOIN {{QbOperatorBankAccount}} b ON b.{{QbOperatorBankAccount.operatorId}} = me.{{QbOperatorMember.operatorId}}
WHERE me.{{QbOperatorMember.removedAt}} IS NULL
