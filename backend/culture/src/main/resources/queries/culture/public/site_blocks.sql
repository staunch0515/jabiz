/*---
id: culture.public.site_blocks
description: Texts of the fixed pages (home, method, about), by key
access: public
cacheSeconds: 60
entities: [SiteBlock]
datasets: { SiteBlock: "urn:jabiz:dataset:public:SiteBlock" }
params:
  keys: { like: SiteBlock.blockKey, list: true, description: The blocks a page shows; all blocks when omitted }
results:
  blockKey: { from: SiteBlock.blockKey }
  body:     { from: SiteBlock.body }
list:
  filters: [blockKey]
  sorts:   [blockKey]
  defaultSort: { field: blockKey, asc: true }
  key: [blockKey]
---*/
SELECT
    b.{{SiteBlock.blockKey}} AS blockKey,
    b.{{SiteBlock.body}}     AS body
FROM {{SiteBlock}} b
WHERE CAST(:keys AS varchar[]) IS NULL OR b.{{SiteBlock.blockKey}} = ANY(:keys)
