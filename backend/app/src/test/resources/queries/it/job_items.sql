/*---
id: it.job_items
description: The due items of one batch of the per-item job tests, in their order
entities: [ItJobItem]
params:
  batch: { like: ItJobItem.batch, required: true }
results:
  itemId: { from: ItJobItem.itemId }
  seq:    { from: ItJobItem.seq }
list:
  sorts: [seq]
  defaultSort: { field: seq, asc: true }
  key: [itemId]
permissions: [it.read]
---*/
SELECT i.{{ItJobItem.itemId}} AS itemId, i.{{ItJobItem.seq}} AS seq
FROM {{ItJobItem}} i
WHERE i.{{ItJobItem.batch}} = :batch AND i.{{ItJobItem.due}}
