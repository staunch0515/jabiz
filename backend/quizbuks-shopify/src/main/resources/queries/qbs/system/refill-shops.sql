/*---
id: qbs.system.refill-shops
description: "The shops whose pool of discount codes the refill job looks at (S2a): a live campaign, or batches being created, retiring or past their end"
entities: [QbsCampaign, QbsDiscountBatch]
datasets:
  QbsCampaign: urn:jabiz:dataset:default:QbsCampaign
  QbsDiscountBatch: urn:jabiz:dataset:default:QbsDiscountBatch
params:
  at: { like: QbsDiscountBatch.endsAt, required: true, description: The scheduled time }
results:
  shopDomain: { from: QbsCampaign.shopDomain }
list:
  sorts: [shopDomain]
  defaultSort: { field: shopDomain, asc: true }
  key: [shopDomain]
permissions: [qbs.system]
---*/
-- A live campaign may need codes; a batch being created may be done; a ready one within seven days of its end
-- retires; a retired one is deleted from the shop once its end passed. The item's process decides what to do.
SELECT c.{{QbsCampaign.shopDomain}} AS shopDomain
FROM {{QbsCampaign}} c
WHERE c.{{QbsCampaign.status}} = 'LIVE'
UNION
SELECT b.{{QbsDiscountBatch.shopDomain}} AS shopDomain
FROM {{QbsDiscountBatch}} b
WHERE b.{{QbsDiscountBatch.status}} IN ('CREATING', 'RETIRED')
   OR (b.{{QbsDiscountBatch.status}} = 'READY'
       AND b.{{QbsDiscountBatch.endsAt}} <= CAST(:at AS timestamptz) + interval '7 days')
