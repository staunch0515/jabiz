/*---
id: qbs.system.plan-shops
description: "The shops whose plan the daily plan job reads from Shopify again (S3a): installed and connected"
entities: [QbsShop]
datasets:
  QbsShop: urn:jabiz:dataset:default:QbsShop
results:
  shopDomain: { from: QbsShop.shopDomain }
list:
  sorts: [shopDomain]
  defaultSort: { field: shopDomain, asc: true }
  key: [shopDomain]
permissions: [qbs.system]
---*/
-- A shop without its access tokens cannot be read: it keeps what was read last until a member connects it again.
SELECT s.{{QbsShop.shopDomain}} AS shopDomain
FROM {{QbsShop}} s
WHERE s.{{QbsShop.status}} = 'ACTIVE'
  AND s.{{QbsShop.connection}} = 'CONNECTED'
