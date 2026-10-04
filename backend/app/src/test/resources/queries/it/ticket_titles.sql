/*---
id: it.ticket_titles
description: The titles of one owner's tickets; the dataset gives at most 5 to a page
entities: [ItTicket]
params:
  owner: { like: ItTicket.owner, required: true }
results:
  title: { from: ItTicket.title }
permissions: [it.query]
---*/
SELECT t.{{ItTicket.title}} AS title FROM {{ItTicket}} t WHERE t.{{ItTicket.owner}} = :owner
