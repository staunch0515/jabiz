/*---
id: it.my_marks_by_ref
description: The caller's marks, through a dataset scoped by a uuid reference to the caller
entities: [ItAccountMark]
datasets: { ItAccountMark: "urn:jabiz:dataset:it:ItAccountMark:mine-by-ref" }
results:
  reason: { from: ItAccountMark.reason }
permissions: [it.query]
---*/
SELECT m.{{ItAccountMark.reason}} AS reason FROM {{ItAccountMark}} m
