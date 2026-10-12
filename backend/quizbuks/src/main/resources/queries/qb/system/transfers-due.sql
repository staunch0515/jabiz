/*---
id: qb.system.transfers-due
description: Takers to transfer to at the given time - transfers on, a cached balance at the threshold, an enabled payout account, not banned, nothing unfinished, not three failures in a row (the items of the job qb.transfer-run)
entities: [QbWallet, QbPayoutAccount, QbTransfer, QbUserBan, SysParam]
datasets:
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbPayoutAccount: urn:jabiz:dataset:default:QbPayoutAccount
  QbTransfer: urn:jabiz:dataset:default:QbTransfer
  QbUserBan: urn:jabiz:dataset:default:QbUserBan
  SysParam: urn:jabiz:dataset:platform:SysParam
timeSlice: { asOf: at }
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The scheduled time of the run (the parameters and bans in effect then) }
results:
  userId:  { kind: { type: text, maxLength: 128 } }
  balance: { from: QbWallet.balance }
list:
  sorts: [userId]
  defaultSort: { field: userId, asc: true }
  key: [userId]
permissions: [qb.payout.system]
---*/
-- The parameters are read here, as in effect at the run's time (a job's selection cannot read business parameters,
-- platform gap 24): with transfers off, or no threshold, nothing is selected. Through the partial index on the takers'
-- balance (V8). Each item's process reads everything again under the taker's wallet lock, from the ledger.
SELECT w.{{QbWallet.ownerId}} AS userId, w.{{QbWallet.balance}} AS balance
FROM {{QbWallet}} w
JOIN {{QbPayoutAccount}} pa ON pa.{{QbPayoutAccount.userId}} = w.{{QbWallet.ownerId}}
CROSS JOIN (
    SELECT max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.transfer.enabled' THEN p.{{SysParam.value}} END) AS p_enabled,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.transfer.threshold' THEN p.{{SysParam.value}} END) AS p_threshold
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} IN ('qb.transfer.enabled', 'qb.transfer.threshold')) params
WHERE w.{{QbWallet.kind}} = 'TAKER'
  -- The time is the slice's (timeSlice above): the parameters and bans as in effect then.
  AND CAST(:at AS timestamptz) IS NOT NULL
  AND params.p_enabled = 'true'
  AND params.p_threshold ~ '^[0-9]{1,18}$'
  AND w.{{QbWallet.balance}} > 0
  AND w.{{QbWallet.balance}} >= CAST(params.p_threshold AS numeric)
  AND pa.{{QbPayoutAccount.status}} = 'ENABLED'
  AND NOT EXISTS (SELECT 1 FROM {{QbUserBan}} b
                  WHERE CAST(b.{{QbUserBan.userId}} AS text) = w.{{QbWallet.ownerId}} AND b.{{QbUserBan.banned}})
  AND NOT EXISTS (SELECT 1 FROM {{QbTransfer}} t
                  WHERE t.{{QbTransfer.userId}} = w.{{QbWallet.ownerId}}
                    AND t.{{QbTransfer.status}} IN ('REVIEW', 'SENDING', 'CHECK'))
  AND (SELECT count(*) FROM (SELECT t2.{{QbTransfer.status}} AS s FROM {{QbTransfer}} t2
                             WHERE t2.{{QbTransfer.userId}} = w.{{QbWallet.ownerId}}
                             ORDER BY t2.{{QbTransfer.createdAt}} DESC LIMIT 3) latest
       WHERE latest.s = 'FAILED') < 3
