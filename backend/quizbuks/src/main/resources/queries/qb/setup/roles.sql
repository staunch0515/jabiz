/*---
id: qb.setup.roles
description: The roles in effect at a point in time; QB_SETUP reads them far ahead to see roles scheduled to begin
entities: [SecRole]
results:
  roleId:   { from: SecRole.roleId }
  roleCode: { from: SecRole.roleCode }
permissions: [qb.setup]
---*/
SELECT
    r.{{SecRole.roleId}}   AS roleId,
    r.{{SecRole.roleCode}} AS roleCode
FROM {{SecRole}} r
