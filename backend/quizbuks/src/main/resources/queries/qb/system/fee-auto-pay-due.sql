/*---
id: qb.system.fee-auto-pay-due
description: "F-M13: the unpaid platform fee invoices of the sponsors that have something to pay them with, the earliest first and at most qb.fee.auto-pay.max-invoices of a sponsor (the items of the daily job qb.fee-auto-pay; none while qb.fee.auto-pay.enabled is not true)"
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
-- run reads it: only the value true is on. An item is one invoice and decides again under the sponsor's wallet lock; it is a transaction of its own.
SELECT sponsorId, invoiceId
FROM (
  SELECT CAST(i.{{QbFeeInvoice.sponsorId}} AS varchar(128)) AS sponsorId,
         CAST(i.{{QbFeeInvoice.invoiceId}} AS varchar(36)) AS invoiceId,
         row_number() OVER (PARTITION BY i.{{QbFeeInvoice.sponsorId}}
                            ORDER BY i.{{QbFeeInvoice.issuedAt}}, i.{{QbFeeInvoice.invoiceNo}}) AS rank
  FROM {{QbFeeInvoice}} i
  WHERE (SELECT max(p.{{SysParam.value}}) FROM {{SysParam}} p WHERE p.{{SysParam.paramKey}} = 'qb.fee.auto-pay.enabled') = 'true'
    AND i.{{QbFeeInvoice.status}} IN ('OPEN', 'PARTIAL', 'OVERDUE')
    AND i.{{QbFeeInvoice.total}} - i.{{QbFeeInvoice.paidAmount}} - i.{{QbFeeInvoice.creditApplied}} > 0
    AND coalesce((SELECT sum(g.{{QbFeeCreditGrant.remaining}}) FROM {{QbFeeCreditGrant}} g
                  WHERE g.{{QbFeeCreditGrant.sponsorId}} = i.{{QbFeeInvoice.sponsorId}}
                    AND g.{{QbFeeCreditGrant.remaining}} > 0 AND g.{{QbFeeCreditGrant.expiresAt}} > :at), 0)
        + coalesce((SELECT sum(greatest(w.{{QbWallet.balance}} - w.{{QbWallet.held}}, 0)) FROM {{QbWallet}} w
                    WHERE w.{{QbWallet.ownerId}} = i.{{QbFeeInvoice.sponsorId}} AND w.{{QbWallet.kind}} = 'CASH'), 0) > 0) due
WHERE rank <= coalesce((SELECT CAST(m.{{SysParam.value}} AS int) FROM {{SysParam}} m
                         WHERE m.{{SysParam.paramKey}} = 'qb.fee.auto-pay.max-invoices'
                           AND m.{{SysParam.value}} ~ '^[0-9]{1,2}$'), 20)
