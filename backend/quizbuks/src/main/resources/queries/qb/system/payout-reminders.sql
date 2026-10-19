/*---
id: qb.system.payout-reminders
description: >-
  Takers whose balance reached the transfer threshold without a payout account that can receive it and who were not
  reminded within qb.payout.reminder-days (the items of the daily job qb.payout-reminder; Q7-16)
entities: [QbWallet, QbPayoutAccount, QbMessage, SysParam]
datasets:
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbPayoutAccount: urn:jabiz:dataset:default:QbPayoutAccount
  QbMessage: urn:jabiz:dataset:default:QbMessage
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The scheduled time of the run }
results:
  userId:  { from: QbWallet.ownerId }
  balance: { from: QbWallet.balance }
list:
  sorts: [balance]
  defaultSort: { field: balance, asc: false }
  key: [userId]
permissions: [qb.messaging.system]
---*/
-- The takers' wallets are the cache of their 2120 (wallet.TakerWallets). Without a published threshold nobody is
-- reminded. QB_PAYOUT_REMINDER reads each one again and reminds only while all of this still holds.
WITH p AS (
    SELECT
        (SELECT CAST(max(v.{{SysParam.value}}) AS numeric) FROM {{SysParam}} v
         WHERE v.{{SysParam.paramKey}} = 'qb.transfer.threshold' AND v.{{SysParam.value}} ~ '^[0-9]{1,15}$') AS threshold,
        coalesce((SELECT CAST(max(v.{{SysParam.value}}) AS int) FROM {{SysParam}} v
                  WHERE v.{{SysParam.paramKey}} = 'qb.payout.reminder-days' AND v.{{SysParam.value}} ~ '^[0-9]{1,3}$'),
                 30) AS days
)
SELECT w.{{QbWallet.ownerId}} AS userId, w.{{QbWallet.balance}} AS balance
FROM {{QbWallet}} w
CROSS JOIN p
WHERE w.{{QbWallet.kind}} = 'TAKER'
  AND p.threshold > 0 AND w.{{QbWallet.balance}} >= p.threshold
  AND NOT EXISTS (SELECT 1 FROM {{QbPayoutAccount}} a
                  WHERE a.{{QbPayoutAccount.userId}} = w.{{QbWallet.ownerId}}
                    AND a.{{QbPayoutAccount.status}} = 'ENABLED')
  AND NOT EXISTS (SELECT 1 FROM {{QbMessage}} m
                  WHERE m.{{QbMessage.recipientId}} = w.{{QbWallet.ownerId}}
                    AND m.{{QbMessage.kind}} = 'PAYOUT_REMINDER'
                    AND m.{{QbMessage.createdAt}} > CAST(:at AS timestamptz) - make_interval(days => p.days))
