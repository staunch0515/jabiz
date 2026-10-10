/*---
id: qb.setup.grants
description: The permissions granted to roles at a point in time; QB_SETUP reads them far ahead to see scheduled grants
entities: [SecRolePermission, SecRole]
results:
  roleCode:   { from: SecRole.roleCode }
  permission: { from: SecRolePermission.permission }
permissions: [qb.setup]
---*/
SELECT
    r.{{SecRole.roleCode}}             AS roleCode,
    g.{{SecRolePermission.permission}} AS permission
FROM {{SecRolePermission}} g
JOIN {{SecRole}} r ON r.{{SecRole.roleId}} = g.{{SecRolePermission.roleId}}
