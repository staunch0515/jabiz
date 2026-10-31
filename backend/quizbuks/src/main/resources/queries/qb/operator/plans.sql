/*---
id: qb.operator.plans
description: "The caller operator's plans (Q48e-2): the five plans' monthly fixed fee and the platform's share, and the changes of its own plan headquarters made (from which month, and whether approved yet); without one, plan 1"
entities: [QbOperatorPlan, QbOperatorMember, SysParam]
datasets:
  QbOperatorPlan: urn:jabiz:dataset:default:QbOperatorPlan
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  kind:           { kind: { type: text, maxLength: 10 } }
  plan:           { from: QbOperatorPlan.plan }
  fixedFee:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  platformShare:  { kind: { type: numeric, precision: 5, scale: 4 } }
  effectiveMonth: { from: QbOperatorPlan.effectiveMonth }
  status:         { from: QbOperatorPlan.status }
  proposedAt:     { from: QbOperatorPlan.proposedAt }
permissions: [qb.operator.use]
---*/
-- Rows of two kinds: TERMS (each plan's terms in force, from the controlled parameters) and CHANGE (the caller's
-- operator's changes of plan, not who proposed them). The page shows the plan of the current month: the approved
-- change with the latest month not after it, plan 1 without one, as the settlement takes it.
WITH plans (n) AS (VALUES ('1'), ('2'), ('3'), ('4'), ('5')),
params AS (
    SELECT p.{{SysParam.paramKey}} AS k, NULLIF(p.{{SysParam.value}}, '') AS v
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} LIKE 'qb.operator.plan.%'
)
SELECT CAST('TERMS' AS varchar(10)) AS kind, CAST(n.n AS varchar(2)) AS plan,
    CAST((SELECT v FROM params WHERE k = 'qb.operator.plan.' || n.n || '.fee') AS numeric(18, 0)) AS fixedFee,
    CAST((SELECT v FROM params WHERE k = 'qb.operator.plan.' || n.n || '.share') AS numeric(5, 4)) AS platformShare,
    CAST(NULL AS varchar(7)) AS effectiveMonth, CAST(NULL AS varchar(10)) AS status,
    CAST(NULL AS timestamptz) AS proposedAt
FROM plans n
UNION ALL
SELECT CAST('CHANGE' AS varchar(10)), c.{{QbOperatorPlan.plan}}, NULL, NULL, c.{{QbOperatorPlan.effectiveMonth}},
    c.{{QbOperatorPlan.status}}, c.{{QbOperatorPlan.proposedAt}}
FROM {{QbOperatorPlan}} c
JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
  AND me.{{QbOperatorMember.operatorId}} = c.{{QbOperatorPlan.operatorId}}
