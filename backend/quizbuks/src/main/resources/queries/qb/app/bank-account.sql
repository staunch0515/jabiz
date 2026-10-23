/*---
id: qb.app.bank-account
description: The taker's bank account for withdrawals and how they are paid (Q13b), with the withdrawal settings - on or off, the fee, the smallest amount and the cooling-off period after a change
entities: [QbBankAccount, SysParam]
datasets:
  QbBankAccount: urn:jabiz:dataset:taker:QbBankAccount
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  bankCode:      { from: QbBankAccount.bankCode }
  bankName:      { from: QbBankAccount.bankName }
  branchCode:    { from: QbBankAccount.branchCode }
  branchName:    { from: QbBankAccount.branchName }
  accountType:   { from: QbBankAccount.accountType }
  accountNumber: { from: QbBankAccount.accountNumber }
  accountHolder: { from: QbBankAccount.accountHolder }
  payoutMethod:  { from: QbBankAccount.payoutMethod }
  changedAt:     { from: QbBankAccount.changedAt }
  enabled:       { kind: { type: bool } }
  fee:           { kind: { type: monetary, currency: JPY, scale: 0 } }
  minimum:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  cooldownHours: { kind: { type: numeric, precision: 4, scale: 0 } }
permissions: [qb.me]
---*/
-- Always one row: the settings, and the caller's own account when they saved one (the number masked, as everywhere).
-- QB_WITHDRAWAL_REQUEST reads the same parameters under the taker's wallet lock and decides.
WITH params AS (
    SELECT p.{{SysParam.paramKey}} AS k, NULLIF(p.{{SysParam.value}}, '') AS v
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} IN ('qb.withdrawal.bank.enabled', 'qb.withdrawal.bank.fee',
        'qb.withdrawal.bank.cooldown-hours', 'qb.transfer.threshold')),
settings AS (
    SELECT
        coalesce(bool_or(k = 'qb.withdrawal.bank.enabled' AND v = 'true'), false)                       AS s_enabled,
        CAST(max(CASE WHEN k = 'qb.withdrawal.bank.fee' AND v ~ '^[0-9]{1,18}$' THEN v END) AS numeric) AS s_fee,
        CAST(max(CASE WHEN k = 'qb.transfer.threshold' AND v ~ '^[0-9]{1,18}$' THEN v END) AS numeric)  AS s_min,
        CAST(max(CASE WHEN k = 'qb.withdrawal.bank.cooldown-hours' AND v ~ '^[0-9]{1,4}$' THEN v END) AS numeric)
                                                                                                        AS s_hours
    FROM params)
SELECT
    a.{{QbBankAccount.bankCode}}      AS bankCode,
    a.{{QbBankAccount.bankName}}      AS bankName,
    a.{{QbBankAccount.branchCode}}    AS branchCode,
    a.{{QbBankAccount.branchName}}    AS branchName,
    a.{{QbBankAccount.accountType}}   AS accountType,
    a.{{QbBankAccount.accountNumber}} AS accountNumber,
    a.{{QbBankAccount.accountHolder}} AS accountHolder,
    a.{{QbBankAccount.payoutMethod}}  AS payoutMethod,
    a.{{QbBankAccount.changedAt}}     AS changedAt,
    s.s_enabled                       AS enabled,
    s.s_fee                           AS fee,
    s.s_min                           AS minimum,
    s.s_hours                         AS cooldownHours
FROM settings s
LEFT JOIN {{QbBankAccount}} a ON true
