/*---
id: qb.admin.operator-health
description: "What is wrong with the operators' structure (F-S1), one row a problem: no default operator (sponsors cannot sign up), a sponsor no operator looks after, an active operator without exactly one own sponsor, an active operator with fewer than two members who can review (its own sponsor's publications then have nobody to review them); empty when all is well"
entities: [QbSponsor, QbSponsorOperator, QbOperator, QbOperatorMember, SecUserRole, SecRole]
datasets:
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperator: urn:jabiz:dataset:default:QbOperator
  QbOperatorMember: urn:jabiz:dataset:default:QbOperatorMember
results:
  checkCode:    { kind: { type: text, maxLength: 30 } }
  operatorId:   { kind: { type: text, maxLength: 36 } }
  operatorName: { kind: { type: text, maxLength: 200 } }
  sponsorId:    { kind: { type: text, maxLength: 128 } }
  found:        { kind: { type: numeric, precision: 9, scale: 0 } }
list:
  sorts: [checkCode]
  defaultSort: { field: checkCode, asc: true }
  key: [checkCode, operatorId, sponsorId]
permissions: [qb.admin.operators.read]
---*/
-- One statement, one snapshot. The daily job QB_OPERATOR_CHECK_RUN asks headquarters when it returns anything; the
-- operators' page shows the same rows.
-- NO_DEFAULT_OPERATOR: no active default operator, so nobody can sign up as a sponsor; found is 0.
SELECT CAST('NO_DEFAULT_OPERATOR' AS varchar(30)) AS checkCode,
       CAST(NULL AS varchar(36)) AS operatorId,
       CAST(NULL AS varchar(200)) AS operatorName,
       CAST(NULL AS varchar(128)) AS sponsorId,
       CAST(0 AS numeric) AS found
WHERE NOT EXISTS (SELECT 1 FROM {{QbOperator}} d
                  WHERE d.{{QbOperator.defaultForNew}} AND d.{{QbOperator.status}} = 'ACTIVE')
UNION ALL
-- NO_OPERATOR: a sponsor (any onboarding) without an assignment; found is 0.
SELECT CAST('NO_OPERATOR' AS varchar(30)),
       CAST(NULL AS varchar(36)),
       CAST(NULL AS varchar(200)),
       CAST(s.{{QbSponsor.userId}} AS varchar(128)),
       CAST(0 AS numeric)
FROM {{QbSponsor}} s
WHERE NOT EXISTS (SELECT 1 FROM {{QbSponsorOperator}} a WHERE a.{{QbSponsorOperator.userId}} = s.{{QbSponsor.userId}})
UNION ALL
-- DIRECT_SPONSOR: an active operator whose own sponsors (fixed assignments) are not exactly one; found is how many.
SELECT CAST('DIRECT_SPONSOR' AS varchar(30)),
       CAST(o.{{QbOperator.operatorId}} AS varchar(36)),
       CAST(o.{{QbOperator.name}} AS varchar(200)),
       CAST(NULL AS varchar(128)),
       CAST((SELECT count(*) FROM {{QbSponsorOperator}} a
              WHERE a.{{QbSponsorOperator.operatorId}} = o.{{QbOperator.operatorId}}
                AND a.{{QbSponsorOperator.fixed}}) AS numeric)
FROM {{QbOperator}} o
WHERE o.{{QbOperator.status}} = 'ACTIVE'
  AND (o.{{QbOperator.directSponsorId}} IS NULL
       OR (SELECT count(*) FROM {{QbSponsorOperator}} a
            WHERE a.{{QbSponsorOperator.operatorId}} = o.{{QbOperator.operatorId}}
              AND a.{{QbSponsorOperator.fixed}}) <> 1)
UNION ALL
-- FEW_REVIEWERS: an active operator with fewer than two current members holding QB_OPERATOR (the role that
-- decides reviews); found is how many.
SELECT CAST('FEW_REVIEWERS' AS varchar(30)),
       CAST(o.{{QbOperator.operatorId}} AS varchar(36)),
       CAST(o.{{QbOperator.name}} AS varchar(200)),
       CAST(NULL AS varchar(128)),
       CAST(r.reviewers AS numeric)
FROM {{QbOperator}} o
CROSS JOIN LATERAL (
    SELECT count(*) AS reviewers
    FROM {{QbOperatorMember}} m
    WHERE m.{{QbOperatorMember.operatorId}} = o.{{QbOperator.operatorId}}
      AND m.{{QbOperatorMember.removedAt}} IS NULL
      AND EXISTS (SELECT 1 FROM {{SecUserRole}} ur JOIN {{SecRole}} sr ON sr.{{SecRole.roleId}} = ur.{{SecUserRole.roleId}}
                   WHERE ur.{{SecUserRole.userId}} = m.{{QbOperatorMember.userId}}
                     AND sr.{{SecRole.roleCode}} = 'QB_OPERATOR' AND sr.{{SecRole.enabled}})
) r
WHERE o.{{QbOperator.status}} = 'ACTIVE' AND r.reviewers < 2
