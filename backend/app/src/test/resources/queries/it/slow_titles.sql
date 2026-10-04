/*---
id: it.slow_titles
description: One owner's ticket titles after a pause, past the template's time limit when the pause is longer
entities: [ItTicket]
params:
  owner: { like: ItTicket.owner, required: true }
  pause: { like: ItTicket.amount, required: true }
results:
  title: { from: ItTicket.title }
timeoutMs: 500
permissions: [it.query]
---*/
SELECT t.{{ItTicket.title}} AS title
FROM {{ItTicket}} t
CROSS JOIN LATERAL (SELECT pg_sleep(CAST(:pause AS numeric)) AS slept) s
WHERE t.{{ItTicket.owner}} = :owner
