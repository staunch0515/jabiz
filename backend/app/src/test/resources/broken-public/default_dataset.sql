/*---
id: it.broken.default_dataset
access: public
entities: [ItAttachment]
results:
  attachmentId: { from: ItAttachment.attachmentId }
---*/
SELECT a.{{ItAttachment.attachmentId}} AS attachmentId FROM {{ItAttachment}} a
