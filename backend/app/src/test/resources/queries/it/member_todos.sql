/*---
id: it.member_todos
description: Todo entries through the member dataset (scope = current actor)
entities: [Todo]
datasets: { Todo: "urn:jabiz:dataset:member:Todo" }
results:
  title:   { from: Todo.title }
  ownerId: { from: Todo.ownerId }
list:
  filters: [title]
  sorts: [title]
  defaultSort: { field: title }
permissions: [it.query]
---*/
SELECT t.{{Todo.title}} AS title, t.{{Todo.ownerId}} AS ownerId
FROM {{Todo}} t
