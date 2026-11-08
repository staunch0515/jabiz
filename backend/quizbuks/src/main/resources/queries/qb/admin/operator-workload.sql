/*---
id: qb.admin.operator-workload
description: "Each operator's reviews (F-S1b): how many wait for its members now, and how many they decided since a time; the platform's home and its monitoring"
entities: [QbOperator, SysApprovalRequest, ApprovalDecision]
datasets:
  QbOperator: urn:jabiz:dataset:default:QbOperator
  SysApprovalRequest: urn:jabiz:dataset:platform:SysApprovalRequest
  ApprovalDecision: urn:jabiz:dataset:platform:ApprovalDecision
params:
  since: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The decisions counted from this time (the client's, 30 days ago by default)" }
results:
  operatorId:    { from: QbOperator.operatorId }
  name:          { from: QbOperator.name }
  status:        { from: QbOperator.status }
  defaultForNew: { from: QbOperator.defaultForNew }
  pending:       { kind: { type: numeric, precision: 9, scale: 0 } }
  decided:       { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  filters: [status, name]
  sorts:   [pending, decided, name]
  defaultSort: { field: pending, asc: false }
  key: [operatorId]
permissions: [qb.admin.operators.read]
---*/
-- The reviews are the platform's approval requests in the operators' entry scope (D64): their scope value is the
-- operator, moved with a reassigned sponsor (D76); a decision keeps the scope it was taken in.
SELECT
    o.{{QbOperator.operatorId}}    AS operatorId,
    o.{{QbOperator.name}}          AS name,
    o.{{QbOperator.status}}        AS status,
    o.{{QbOperator.defaultForNew}} AS defaultForNew,
    (SELECT count(*) FROM {{SysApprovalRequest}} r
      WHERE r.{{SysApprovalRequest.scopeName}} = 'operator'
        AND r.{{SysApprovalRequest.scopeValue}} = o.{{QbOperator.operatorId}}
        AND r.{{SysApprovalRequest.status}} = 'PENDING') AS pending,
    (SELECT count(*) FROM {{ApprovalDecision}} d
      WHERE d.{{ApprovalDecision.scopeName}} = 'operator'
        AND d.{{ApprovalDecision.scopeValue}} = o.{{QbOperator.operatorId}}
        AND d.{{ApprovalDecision.decidedTime}} >= CAST(:since AS timestamptz)) AS decided
FROM {{QbOperator}} o
