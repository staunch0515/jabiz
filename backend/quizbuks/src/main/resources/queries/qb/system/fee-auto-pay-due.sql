/*---
id: qb.system.fee-auto-pay-due
description: "F-M13: the sponsors with an unpaid platform fee invoice and something to pay it with (the items of the daily job qb.fee-auto-pay, each starting a chain of at most qb.fee.auto-pay.max-invoices payments; none while qb.fee.auto-pay.enabled is not true)"
entities: [QbFeeInvoice, QbFeeCreditGrant, QbWallet, SysParam]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbWallet: urn:jabiz:dataset:default:QbWallet
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at: { like: QbFeeCreditGrant.expiresAt, required: true, description: "The run's time: a lot valid beyond it is something to pay with" }
results:
  sponsorId: { kind: { type: text, maxLength: 128 } }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.fee.system]
---*/
-- Every way the account grows that sends no event (a prepayment given back, an invoice voided, a credit note's cash,
-- a dispute released, an entry fee, Kudos sold back) is found here the next night: an unpaid invoice (OPEN, PARTIAL,
-- OVERDUE) with something owed, and the fee credit not expired plus the cash not held. The switch is read as the
-- run reads it: only the value true is on. An item is one sponsor: the run decides again under the wallet lock and
-- starts a chain of payments (one mail at its end).
SELECT DISTINCT CAST(i.{{QbFeeInvoice.sponsorId}} AS varchar(128)) AS sponsorId
FROM {{QbFeeInvoice}} i
WHERE (SELECT max(p.{{SysParam.value}}) FROM {{SysParam}} p WHERE p.{{SysParam.paramKey}} = 'qb.fee.auto-pay.enabled') = 'true'
  AND i.{{QbFeeInvoice.status}} IN ('OPEN', 'PARTIAL', 'OVERDUE')
  AND i.{{QbFeeInvoice.total}} - i.{{QbFeeInvoice.paidAmount}} - i.{{QbFeeInvoice.creditApplied}} > 0
  AND coalesce((SELECT sum(g.{{QbFeeCreditGrant.remaining}}) FROM {{QbFeeCreditGrant}} g
                WHERE g.{{QbFeeCreditGrant.sponsorId}} = i.{{QbFeeInvoice.sponsorId}}
                  AND g.{{QbFeeCreditGrant.remaining}} > 0 AND g.{{QbFeeCreditGrant.expiresAt}} > :at), 0)
      + coalesce((SELECT sum(greatest(w.{{QbWallet.balance}} - w.{{QbWallet.held}}, 0)) FROM {{QbWallet}} w
                  WHERE w.{{QbWallet.ownerId}} = i.{{QbFeeInvoice.sponsorId}} AND w.{{QbWallet.kind}} = 'CASH'), 0) > 0
