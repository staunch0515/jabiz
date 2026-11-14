/*---
id: qb.system.money-close-unregistered
description: "The erased users who had the platform fee account and have no wind-up registered (the items of the daily job qb.sponsor-closure-register, F-M15 b §9 M1): the registration's event was lost"
entities: [QbSponsorClosure, QbFeeCreditGrant, QbFeeInvoice, QbWallet, QbPrepayment, QbFeeCharge, QbTopUp, QbFeeRequest, Erasure, SysParam]
datasets:
  QbSponsorClosure: urn:jabiz:dataset:default:QbSponsorClosure
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbTopUp: urn:jabiz:dataset:default:QbTopUp
  QbFeeRequest: urn:jabiz:dataset:default:QbFeeRequest
  Erasure: urn:jabiz:dataset:platform:Erasure
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  at: { like: QbSponsorClosure.erasedAt, required: true, description: "The run's time: erasures within qb.erasure.close.register-days before it" }
results:
  userId: { kind: { type: text, maxLength: 128 } }
  erasedTime: { from: Erasure.erasedTime }
list:
  sorts: [userId]
  defaultSort: { field: userId, asc: true }
  key: [userId]
permissions: [qb.fee.system]
---*/
-- "Had the account" is QB_SPONSOR_CLOSURE_REGISTER's definition (a CASH or FEE_CREDIT wallet, a grant, an invoice, a
-- prepayment, a postpaid fee, a manual credit or a fee request under review), as the reconciliation's
-- ERASED_NO_CLOSURE (qb.system.recon-erased): write the three together. The item reads everything again.
-- Only erasures of the last qb.erasure.close.register-days days (re-review L9: the sweep does not scan every erasure
-- ever made; a whole number from 1 to 365, else 30); ERASED_NO_CLOSURE still checks them all.
SELECT CAST(e.{{Erasure.userId}} AS varchar(128)) AS userId, e.{{Erasure.erasedTime}} AS erasedTime
FROM {{Erasure}} e
WHERE e.{{Erasure.erasedTime}} >= CAST(:at AS timestamptz) - make_interval(days => coalesce(
        (SELECT CAST(p.{{SysParam.value}} AS int) FROM {{SysParam}} p
         WHERE p.{{SysParam.paramKey}} = 'qb.erasure.close.register-days' AND p.{{SysParam.value}} ~ '^[0-9]{1,3}$'
           AND CAST(p.{{SysParam.value}} AS int) BETWEEN 1 AND 365), 30))
  AND NOT EXISTS (SELECT 1 FROM {{QbSponsorClosure}} nc
                  WHERE nc.{{QbSponsorClosure.sponsorId}} = CAST(e.{{Erasure.userId}} AS text))
  AND (EXISTS (SELECT 1 FROM {{QbWallet}} w
               WHERE w.{{QbWallet.ownerId}} = CAST(e.{{Erasure.userId}} AS text)
                 AND w.{{QbWallet.kind}} IN ('CASH', 'FEE_CREDIT'))
       OR EXISTS (SELECT 1 FROM {{QbFeeCreditGrant}} ng
                  WHERE ng.{{QbFeeCreditGrant.sponsorId}} = CAST(e.{{Erasure.userId}} AS text))
       OR EXISTS (SELECT 1 FROM {{QbFeeInvoice}} ni
                  WHERE ni.{{QbFeeInvoice.sponsorId}} = CAST(e.{{Erasure.userId}} AS text))
       OR EXISTS (SELECT 1 FROM {{QbPrepayment}} np
                  WHERE np.{{QbPrepayment.sponsorId}} = CAST(e.{{Erasure.userId}} AS text))
       OR EXISTS (SELECT 1 FROM {{QbFeeCharge}} nf
                  WHERE nf.{{QbFeeCharge.sponsorId}} = CAST(e.{{Erasure.userId}} AS text)
                    AND nf.{{QbFeeCharge.billingMode}} = 'POSTPAID')
       OR EXISTS (SELECT 1 FROM {{QbTopUp}} nt
                  WHERE nt.{{QbTopUp.sponsorId}} = CAST(e.{{Erasure.userId}} AS text)
                    AND nt.{{QbTopUp.method}} = 'MANUAL' AND nt.{{QbTopUp.status}} = 'REVIEW')
       OR EXISTS (SELECT 1 FROM {{QbFeeRequest}} nr
                  WHERE nr.{{QbFeeRequest.sponsorId}} = CAST(e.{{Erasure.userId}} AS text)
                    AND nr.{{QbFeeRequest.status}} = 'REVIEW'))
