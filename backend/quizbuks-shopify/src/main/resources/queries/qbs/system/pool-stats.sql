/*---
id: qbs.system.pool-stats
description: "A shop's pool of discount codes (S2a): the codes its storefront can still hand out, and those handed out today and over the week"
entities: [QbsDiscountCode, QbsDiscountBatch]
datasets:
  QbsDiscountCode: urn:jabiz:dataset:default:QbsDiscountCode
  QbsDiscountBatch: urn:jabiz:dataset:default:QbsDiscountBatch
params:
  shopDomain:  { like: QbsDiscountCode.shopDomain, required: true, description: The shop }
  usableUntil: { like: QbsDiscountBatch.endsAt, required: true, description: A code is handed out only when its batch ends after this }
  dayStart:    { like: QbsDiscountCode.issuedAt, required: true, description: "Today's start (UTC)" }
  weekStart:   { like: QbsDiscountCode.issuedAt, required: true, description: "The start of six days before today (UTC)" }
results:
  available:   { kind: { type: numeric, precision: 9, scale: 0 } }
  issuedToday: { kind: { type: numeric, precision: 9, scale: 0 } }
  issuedWeek:  { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  key: [available]
permissions: [qbs.system]
---*/
-- One row. Through qbs_discount_code_pool_idx and qbs_discount_code_issued_idx.
SELECT
    (SELECT count(*) FROM {{QbsDiscountCode}} c JOIN {{QbsDiscountBatch}} b
        ON b.{{QbsDiscountBatch.batchId}} = c.{{QbsDiscountCode.batchId}}
     WHERE c.{{QbsDiscountCode.shopDomain}} = :shopDomain AND c.{{QbsDiscountCode.status}} = 'AVAILABLE'
       AND b.{{QbsDiscountBatch.status}} = 'READY' AND b.{{QbsDiscountBatch.endsAt}} > :usableUntil) AS available,
    (SELECT count(*) FROM {{QbsDiscountCode}} c
     WHERE c.{{QbsDiscountCode.shopDomain}} = :shopDomain AND c.{{QbsDiscountCode.issuedAt}} >= :dayStart) AS issuedToday,
    (SELECT count(*) FROM {{QbsDiscountCode}} c
     WHERE c.{{QbsDiscountCode.shopDomain}} = :shopDomain AND c.{{QbsDiscountCode.issuedAt}} >= :weekStart) AS issuedWeek
