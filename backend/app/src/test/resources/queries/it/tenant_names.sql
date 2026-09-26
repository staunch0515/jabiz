/*---
id: it.tenant_names
description: Names of the caller's tenant (scope from the request)
entities: [ItTenant]
results:
  name: { from: ItTenant.name }
permissions: [it.query]
---*/
SELECT t.{{ItTenant.name}} AS name FROM {{ItTenant}} t
