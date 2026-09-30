/*---
id: jabiz.security.access_review
description: >-
  Who could do what at a time, for the periodic access review: every user with each role assigned to them (and the
  data period of the assignment), each permission of the role, and the user's last successful sign-in up to that
  time. Issued with REPORT_ISSUE as of the end of the reviewed period and signed with ACCESS_REVIEW_SIGN_OFF
  (docs/design/10-security.md section 13.3).
entities: [SecUser, SecRole, SecUserRole, SecRolePermission, SecLoginRecord]
params:
  asOf: { like: SecLoginRecord.attemptTime, required: true, description: the time the access is shown at }
results:
  userName:    { from: SecUser.userName }
  displayName: { from: SecUser.displayName }
  userEnabled: { from: SecUser.enabled }
  roleCode:    { from: SecRole.roleCode }
  roleEnabled: { from: SecRole.enabled }
  requireMfa:  { from: SecRole.requireMfa }
  dataFrom:    { from: SecUserRole.dataFrom }
  dataTo:      { from: SecUserRole.dataTo }
  permission:  { from: SecRolePermission.permission }
  lastSignIn:  { from: SecLoginRecord.attemptTime }
list:
  filters: [userName, userEnabled, roleCode, roleEnabled, permission]
  sorts:   [userName, roleCode, permission, lastSignIn]
  defaultSort: { field: userName, asc: true }
  key: [userName, roleCode, permission]
permissions: [security.access-review.read]
# Every security entity is read as in effect at asOf, so the report shows the access of that moment; issuing it
# pins the recorded time too, so that it can be verified later (docs/design/19-reports.md section 5).
timeSlice: { asOf: asOf }
report:
  period: { to: asOf }
---*/
WITH last_sign_in AS (
    SELECT l.{{SecLoginRecord.userId}} AS login_user, MAX(l.{{SecLoginRecord.attemptTime}}) AS last_time
    FROM {{SecLoginRecord}} l
    WHERE l.{{SecLoginRecord.outcome}} = 'SUCCESS' AND l.{{SecLoginRecord.attemptTime}} <= :asOf
    GROUP BY l.{{SecLoginRecord.userId}}
)
SELECT
    u.{{SecUser.userName}}             AS userName,
    u.{{SecUser.displayName}}          AS displayName,
    u.{{SecUser.enabled}}              AS userEnabled,
    r.{{SecRole.roleCode}}             AS roleCode,
    r.{{SecRole.enabled}}              AS roleEnabled,
    COALESCE(r.{{SecRole.requireMfa}}, false) AS requireMfa,
    ur.{{SecUserRole.dataFrom}}        AS dataFrom,
    ur.{{SecUserRole.dataTo}}          AS dataTo,
    p.{{SecRolePermission.permission}} AS permission,
    s.last_time                        AS lastSignIn
FROM {{SecUser}} u
LEFT JOIN {{SecUserRole}} ur ON ur.{{SecUserRole.userId}} = u.{{SecUser.userId}}
LEFT JOIN {{SecRole}} r ON r.{{SecRole.roleId}} = ur.{{SecUserRole.roleId}}
LEFT JOIN {{SecRolePermission}} p ON p.{{SecRolePermission.roleId}} = r.{{SecRole.roleId}}
LEFT JOIN last_sign_in s ON s.login_user = u.{{SecUser.userId}}
