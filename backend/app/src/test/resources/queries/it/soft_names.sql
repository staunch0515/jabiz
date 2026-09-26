/*---
id: it.soft_names
description: Names of ItSoft rows; the dataset soft-deletes
entities: [ItSoft]
results:
  name: { from: ItSoft.name }
permissions: [it.query]
---*/
SELECT s.{{ItSoft.name}} AS name FROM {{ItSoft}} s
