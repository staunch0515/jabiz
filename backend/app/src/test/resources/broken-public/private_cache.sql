/*---
id: it.broken.private_cache
permissions: [it.query]
cacheSeconds: 30
entities: [ItAttachment]
results:
  attachmentId: { from: ItAttachment.attachmentId }
---*/
SELECT a.{{ItAttachment.attachmentId}} AS attachmentId FROM {{ItAttachment}} a
