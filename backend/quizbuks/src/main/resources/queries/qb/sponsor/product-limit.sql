/*---
id: qb.sponsor.product-limit
description: >-
  F-C11: the most products the sponsor may have (qb.products.max), as QB_PRODUCT_SAVE and the profile package's check
  read it; for the products page
entities: [SysParam]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  max: { kind: { type: numeric, precision: 9, scale: 0 } }
permissions: [qb.sponsor.me]
---*/
-- One row, at the server's clock (the dataset without time travel). The rule is the processes'
-- (SponsorProductProcesses.maxProducts decides; this is for the page). A parameter not set (before setup) or not a
-- whole number leaves max null: the page then sets no limit of its own and leaves it to the save.
SELECT
    CAST(CASE WHEN max(p.{{SysParam.value}}) ~ '^[0-9]{1,9}$' THEN CAST(max(p.{{SysParam.value}}) AS numeric) END
        AS numeric(9, 0)) AS max
FROM {{SysParam}} p
WHERE p.{{SysParam.paramKey}} = 'qb.products.max'
