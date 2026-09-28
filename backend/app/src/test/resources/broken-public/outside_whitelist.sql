/*---
id: it.broken.outside_whitelist
access: public
entities: [ItAttachment]
datasets: { ItAttachment: "urn:jabiz:dataset:it:public:ItAttachment" }
results:
  title: { from: ItAttachment.title }
---*/
SELECT a.{{ItAttachment.title}} AS title
FROM {{ItAttachment}} a
