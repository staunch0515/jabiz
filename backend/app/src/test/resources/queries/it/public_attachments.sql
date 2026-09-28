/*---
id: it.public.attachments
description: Public attachments of the integration tests (an ordinary table read through a public dataset)
access: public
cacheSeconds: 0
entities: [ItAttachment]
datasets: { ItAttachment: "urn:jabiz:dataset:it:public:ItAttachment" }
results:
  attachmentId: { from: ItAttachment.attachmentId }
  cover:        { from: ItAttachment.cover }
list:
  filters: [attachmentId]
  sorts:   [attachmentId]
  defaultSort: { field: attachmentId, asc: true }
---*/
SELECT a.{{ItAttachment.attachmentId}} AS attachmentId, a.{{ItAttachment.cover}} AS cover
FROM {{ItAttachment}} a
