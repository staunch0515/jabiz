/*---
id: it.broken.public_and_permissions
access: public
permissions: [it.query]
timeoutMs: 3000
entities: [ItAttachment]
datasets: { ItAttachment: "urn:jabiz:dataset:it:public:ItAttachment" }
results:
  attachmentId: { from: ItAttachment.attachmentId }
---*/
SELECT a.{{ItAttachment.attachmentId}} AS attachmentId FROM {{ItAttachment}} a
