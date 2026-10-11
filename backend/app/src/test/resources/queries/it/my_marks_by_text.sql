/*---
id: it.my_marks_by_text
description: The caller's marks, through a dataset scoped by a text column holding the caller
entities: [ItAccountMark]
datasets: { ItAccountMark: "urn:jabiz:dataset:it:ItAccountMark:mine-by-text" }
results:
  reason: { from: ItAccountMark.reason }
permissions: [it.query]
---*/
SELECT m.{{ItAccountMark.reason}} AS reason FROM {{ItAccountMark}} m
