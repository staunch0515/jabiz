/*---
id: qb.admin.operator-members
description: "An operator's members (Q48a), the removed ones too, with whether they hold the role that lets them sign in"
entities: [QbOperatorMember, SecUser, SecUserRole, SecRole]
datasets:
  QbOperatorMember: urn:jabiz:dataset:default:QbOperatorMember
params:
  operatorId: { like: QbOperatorMember.operatorId, required: true, description: The operator }
results:
  memberId:    { from: QbOperatorMember.memberId }
  userId:      { from: QbOperatorMember.userId }
  userName:    { from: SecUser.userName }
  displayName: { from: SecUser.displayName }
  email:       { from: SecUser.email }
  lead:        { from: QbOperatorMember.lead }
  addedAt:     { from: QbOperatorMember.addedAt }
  addedBy:     { from: QbOperatorMember.addedBy }
  removedAt:   { from: QbOperatorMember.removedAt }
  removedBy:   { from: QbOperatorMember.removedBy }
  hasRole:     { kind: { type: bool } }
list:
  filters: [lead, removedAt]
  sorts:   [addedAt, removedAt]
  defaultSort: { field: addedAt, asc: false }
  key: [memberId]
permissions: [qb.admin.operators.read]
---*/
-- hasRole: whether the person holds QB_OPERATOR now (given through the platform's role assignment and its four eyes):
-- a member without it cannot sign in at the operators' entry.
SELECT
    m.{{QbOperatorMember.memberId}}  AS memberId,
    m.{{QbOperatorMember.userId}}    AS userId,
    u.{{SecUser.userName}}           AS userName,
    u.{{SecUser.displayName}}        AS displayName,
    u.{{SecUser.email}}              AS email,
    m.{{QbOperatorMember.lead}}      AS lead,
    m.{{QbOperatorMember.addedAt}}   AS addedAt,
    m.{{QbOperatorMember.addedBy}}   AS addedBy,
    m.{{QbOperatorMember.removedAt}} AS removedAt,
    m.{{QbOperatorMember.removedBy}} AS removedBy,
    EXISTS (SELECT 1 FROM {{SecUserRole}} ur JOIN {{SecRole}} r ON r.{{SecRole.roleId}} = ur.{{SecUserRole.roleId}}
             WHERE ur.{{SecUserRole.userId}} = m.{{QbOperatorMember.userId}}
               AND r.{{SecRole.roleCode}} = 'QB_OPERATOR' AND r.{{SecRole.enabled}}) AS hasRole
FROM {{QbOperatorMember}} m
LEFT JOIN {{SecUser}} u ON u.{{SecUser.userId}} = m.{{QbOperatorMember.userId}}
WHERE m.{{QbOperatorMember.operatorId}} = :operatorId
