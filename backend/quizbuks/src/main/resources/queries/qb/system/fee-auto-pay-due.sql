/*---
id: qb.system.fee-auto-pay-due
description: "F-M13: the unpaid platform fee invoices of the sponsors that have something to pay them with, the earliest first and at most qb.fee.auto-pay.max-invoices of a sponsor (the items of the daily job qb.fee-auto-pay; none while qb.fee.auto-pay.enabled is not true)"
entities: [QbFeeInvoice, QbFeeCreditGrant, QbWallet, SysParam, QbSponsorClosure]
datasets:
  QbSponsorClosure: urn:jabiz:dataset:default:QbSponsorClosure
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbWallet: urn:jabiz:dataset:default:QbWallet
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at: { like: QbFeeCreditGrant.expiresAt, required: true, description: "The run's time: a lot valid beyond it is something to pay with" }
results:
  sponsorId: { kind: { type: text, maxLength: 128 } }
  invoiceId: { kind: { type: text, maxLength: 36 } }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [invoiceId]
permissions: [qb.fee.system]
---*/
-- Every way the account grows that sends no event (a prepayment given back, an invoice voided, a credit note's cash,
-- a dispute released, an entry fee, Kudos sold back) is found here the next night: an unpaid invoice (OPEN, PARTIAL,
-- OVERDUE) with something owed, and the fee credit not expired plus the cash not held. The switch is read as the
-- run reads it: only the value true is on. An erased sponsor's account is the wind-up's (F-M15 b, re-review L3: with
-- its switch off a wind-up only records; nothing else pays or voids): a sponsor with a closure is not an item. An item is one invoice and decides again under the sponsor's wallet lock; it is a transaction of its own.
SELECT sponsorId, invoiceId
FROM (
  SELECT CAST(i.{{QbFeeInvoice.sponsorId}} AS varchar(128)) AS sponsorId,
         CAST(i.{{QbFeeInvoice.invoiceId}} AS varchar(36)) AS invoiceId,
         row_number() OVER (PARTITION BY i.{{QbFeeInvoice.sponsorId}}
                            ORDER BY i.{{QbFeeInvoice.issuedAt}}, i.{{QbFeeInvoice.invoiceNo}}) AS rank
  FROM {{QbFeeInvoice}} i
  WHERE (SELECT max(p.{{SysParam.value}}) FROM {{SysParam}} p WHERE p.{{SysParam.paramKey}} = 'qb.fee.auto-pay.enabled') = 'true'
    AND i.{{QbFeeInvoice.status}} IN ('OPEN', 'PARTIAL', 'OVERDUE')
    AND NOT EXISTS (SELECT 1 FROM {{QbSponsorClosure}} c
                    WHERE c.{{QbSponsorClosure.sponsorId}} = i.{{QbFeeInvoice.sponsorId}})
    AND i.{{QbFeeInvoice.total}} - i.{{QbFeeInvoice.paidAmount}} - i.{{QbFeeInvoice.creditApplied}} > 0
    AND coalesce((SELECT sum(g.{{QbFeeCreditGrant.remaining}}) FROM {{QbFeeCreditGrant}} g
                  WHERE g.{{QbFeeCreditGrant.sponsorId}} = i.{{QbFeeInvoice.sponsorId}}
                    AND g.{{QbFeeCreditGrant.remaining}} > 0 AND g.{{QbFeeCreditGrant.expiresAt}} > :at), 0)
        + coalesce((SELECT sum(greatest(w.{{QbWallet.balance}} - w.{{QbWallet.held}}, 0)) FROM {{QbWallet}} w
                    WHERE w.{{QbWallet.ownerId}} = i.{{QbFeeInvoice.sponsorId}} AND w.{{QbWallet.kind}} = 'CASH'), 0) > 0) due
WHERE rank <= coalesce((SELECT least(greatest(CAST(m.{{SysParam.value}} AS int), 1), 50) FROM {{SysParam}} m
                         WHERE m.{{SysParam.paramKey}} = 'qb.fee.auto-pay.max-invoices'
                           AND m.{{SysParam.value}} ~ '^[0-9]{1,2}$'), 20)
