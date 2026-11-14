/*---
id: qb.system.money-close-due
description: "The erased sponsors whose platform fee account is to be wound up (the items of the daily job qb.sponsor-money-close, F-M15 b §5.6): not wound up yet, or wound up with fees owed and cash that arrived since"
entities: [QbSponsorClosure, QbCashLot]
datasets:
  QbSponsorClosure: urn:jabiz:dataset:default:QbSponsorClosure
  QbCashLot: urn:jabiz:dataset:default:QbCashLot
results:
  sponsorId: { from: QbSponsorClosure.sponsorId }
list:
  sorts: [sponsorId]
  defaultSort: { field: sponsorId, asc: true }
  key: [sponsorId]
permissions: [qb.fee.system]
---*/
-- Wound up with fees owed (M8): cash that arrived after it (a top-up confirmed late, an invoice voided) is in a lot with
-- something left, and pays again. The item reads everything again under the sponsor's wallet lock.
SELECT c.{{QbSponsorClosure.sponsorId}} AS sponsorId
FROM {{QbSponsorClosure}} c
WHERE c.{{QbSponsorClosure.status}} = 'WAITING'
   OR (c.{{QbSponsorClosure.status}} = 'CLOSED'
       AND c.{{QbSponsorClosure.reasonCode}} = 'FEE_OWED'
       AND EXISTS (SELECT 1 FROM {{QbCashLot}} l
                   WHERE l.{{QbCashLot.sponsorId}} = c.{{QbSponsorClosure.sponsorId}}
                     AND l.{{QbCashLot.remaining}} > 0))
