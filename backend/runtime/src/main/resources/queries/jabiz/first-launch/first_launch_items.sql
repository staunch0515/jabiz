/*---
id: jabiz.first_launch_items
description: >-
  The items of the job jabiz.first-launch while the first launch waits for its accounts (docs/design/23-first-launch.md
  section 8): the run's content phase is done and it has not ended. Each account three times - its invitations,
  ordinary grants and the waiting role's withdrawal (key: the user id), the application's steps once it is ready (key: r
  and the user id without hyphens, section 9), then its administrator's grants (key: x and the user id without
  hyphens) - and last the run's end (key: end). Administrators come after everyone else, so the setup window
  of protected grants stays open for the others.
entities: [FirstLaunchAccount, FirstLaunchEvent]
results:
  itemKey:  { kind: { type: text, maxLength: 36 } }
  ordering: { kind: { type: numeric, precision: 18, scale: 0 } }
list:
  sorts: [ordering]
  defaultSort: { field: ordering, asc: true }
  key: [itemKey]
permissions: [first-launch.run]
---*/
SELECT i.itemKey AS itemKey, i.ordering AS ordering
FROM (
    SELECT CASE p.phase WHEN 0 THEN a.{{FirstLaunchAccount.userId}}::text
               WHEN 1 THEN 'r' || replace(a.{{FirstLaunchAccount.userId}}::text, '-', '')
               WHEN 2 THEN 'x' || replace(a.{{FirstLaunchAccount.userId}}::text, '-', '')
               ELSE 'end' END AS itemKey,
           p.phase * 1000000 + a.{{FirstLaunchAccount.position}} AS ordering,
           p.phase AS phase, a.{{FirstLaunchAccount.position}} AS pos
    FROM {{FirstLaunchAccount}} a
    CROSS JOIN (SELECT 0 AS phase UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3) p
    WHERE EXISTS (SELECT 1 FROM {{FirstLaunchEvent}} e
                  WHERE e.{{FirstLaunchEvent.runId}} = a.{{FirstLaunchAccount.runId}}
                    AND e.{{FirstLaunchEvent.kind}} = 'CONTENT_DONE')
      AND NOT EXISTS (SELECT 1 FROM {{FirstLaunchEvent}} e
                      WHERE e.{{FirstLaunchEvent.runId}} = a.{{FirstLaunchAccount.runId}}
                        AND e.{{FirstLaunchEvent.kind}} IN ('DONE', 'EXPIRED', 'FAILED'))
) i
WHERE i.phase < 3 OR i.pos = 0
