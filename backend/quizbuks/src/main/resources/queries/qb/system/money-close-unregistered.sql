/*---
id: qb.system.money-close-unregistered
description: "The erased users who had the platform fee account and have no wind-up registered (the items of the daily job qb.sponsor-closure-register, F-M15 b §9 M1): the registration's event was lost"
entities: [QbSponsorClosure, QbFeeCreditGrant, QbFeeInvoice, QbWallet, QbPrepayment, Erasure]
datasets:
  QbSponsorClosure: urn:jabiz:dataset:default:QbSponsorClosure
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  Erasure: urn:jabiz:dataset:platform:Erasure
results:
  userId: { kind: { type: text, maxLength: 128 } }
  erasedTime: { from: Erasure.erasedTime }
list:
  sorts: [userId]
  defaultSort: { field: userId, asc: true }
  key: [userId]
permissions: [qb.fee.system]
---*/
-- "Had the account" is QB_SPONSOR_CLOSURE_REGISTER's definition (a CASH or FEE_CREDIT wallet, a grant, an invoice or a
-- prepayment), as the reconciliation's ERASED_NO_CLOSURE (qb.system.recon-erased): write the three together. The item
-- reads everything again.
SELECT CAST(e.{{Erasure.userId}} AS varchar(128)) AS userId, e.{{Erasure.erasedTime}} AS erasedTime
FROM {{Erasure}} e
WHERE NOT EXISTS (SELECT 1 FROM {{QbSponsorClosure}} nc
                  WHERE nc.{{QbSponsorClosure.sponsorId}} = CAST(e.{{Erasure.userId}} AS text))
  AND (EXISTS (SELECT 1 FROM {{QbWallet}} w
               WHERE w.{{QbWallet.ownerId}} = CAST(e.{{Erasure.userId}} AS text)
                 AND w.{{QbWallet.kind}} IN ('CASH', 'FEE_CREDIT'))
       OR EXISTS (SELECT 1 FROM {{QbFeeCreditGrant}} ng
                  WHERE ng.{{QbFeeCreditGrant.sponsorId}} = CAST(e.{{Erasure.userId}} AS text))
       OR EXISTS (SELECT 1 FROM {{QbFeeInvoice}} ni
                  WHERE ni.{{QbFeeInvoice.sponsorId}} = CAST(e.{{Erasure.userId}} AS text))
       OR EXISTS (SELECT 1 FROM {{QbPrepayment}} np
                  WHERE np.{{QbPrepayment.sponsorId}} = CAST(e.{{Erasure.userId}} AS text)))
