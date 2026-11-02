/*---
id: qb.sponsor.tree-people
description: >-
  The sponsor's customers on one node of their tree (Q85, D-Q85-3), in one state: who lit it, answered it without
  passing, is on it, could start it, or is locked out of it; each by their profile's name and the key the
  transactions page filters by
entities: [QbPublication, QbPublicationPrerequisite, QbAttempt, QbProfile, QbConversation]
datasets:
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbPublicationPrerequisite: urn:jabiz:dataset:sponsor:QbPublicationPrerequisite
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
  QbProfile: urn:jabiz:dataset:default:QbProfile
  QbConversation: urn:jabiz:dataset:sponsor:QbConversation
params:
  publicationId: { like: QbPublication.publicationId, required: true, description: The node }
  state:         { kind: { type: text, maxLength: 12 }, required: true, description: "LIT, ANSWERED, IN_PROGRESS, UNLOCKED or LOCKED (tree.TreeStates)" }
  at:            { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time the tree is for (the client's now) }
results:
  userKey:         { kind: { type: text, maxLength: 32 } }
  userName:        { from: QbProfile.displayName }
  state:           { kind: { type: text, maxLength: 12 } }
  finishedAt:      { from: QbAttempt.submittedAt }
  hasConversation: { kind: { type: bool } }
list:
  key: [userKey]
  sorts: [finishedAt, userName]
  defaultSort: { field: finishedAt, asc: false }
permissions: [qb.publication.write]
---*/
-- The same states as qb.sponsor.tree, one person per row (tree.TreeStates; TreeIT compares). The taker shows by
-- their profile's name and userKey, md5(sponsor : user), as on the transactions page (Q9b): not their id, and a
-- different key with each sponsor. Nothing else of the taker is read (D-Q85-5): not their score, not what they are
-- missing. The node must be the sponsor's own (their view): somebody else's gives no rows.
WITH node AS (
    SELECT p.{{QbPublication.publicationId}} AS id,
           p.{{QbPublication.publisherId}}   AS publisher_id,
           p.{{QbPublication.status}} = 'LIVE' AND p.{{QbPublication.startAt}} <= CAST(:at AS timestamptz)
               AND p.{{QbPublication.endAt}} > CAST(:at AS timestamptz) AS live
    FROM {{QbPublication}} p
    WHERE p.{{QbPublication.publicationId}} = :publicationId AND p.{{QbPublication.status}} <> 'DRAFT'
),
customer AS (
    SELECT DISTINCT a.{{QbAttempt.userId}} AS user_id
    FROM {{QbAttempt}} a
    JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = a.{{QbAttempt.publicationId}}
    WHERE p.{{QbPublication.status}} <> 'DRAFT' AND a.{{QbAttempt.status}} <> 'IN_PROGRESS'
),
person AS (
    -- Those with an attempt on the node: a pass, a failure, or one under way (a pass wins over the others).
    SELECT a.{{QbAttempt.userId}} AS user_id,
           CASE WHEN bool_or(a.{{QbAttempt.status}} <> 'IN_PROGRESS' AND coalesce(a.{{QbAttempt.passed}}, false)) THEN 'LIT'
                WHEN bool_or(a.{{QbAttempt.status}} <> 'IN_PROGRESS') THEN 'ANSWERED'
                ELSE 'IN_PROGRESS' END AS state,
           max(a.{{QbAttempt.submittedAt}}) AS finished_at
    FROM {{QbAttempt}} a
    JOIN node n ON n.id = a.{{QbAttempt.publicationId}}
    GROUP BY a.{{QbAttempt.userId}}
    UNION ALL
    -- The sponsor's other customers: locked out, or free to start while the node is open.
    SELECT c.user_id,
           CASE WHEN EXISTS (SELECT 1 FROM {{QbPublicationPrerequisite}} pr
                             WHERE pr.{{QbPublicationPrerequisite.publicationId}} = n.id
                               AND NOT EXISTS (SELECT 1 FROM {{QbAttempt}} ra
                                               WHERE ra.{{QbAttempt.userId}} = c.user_id
                                                 AND ra.{{QbAttempt.publicationId}} = pr.{{QbPublicationPrerequisite.requiredPublicationId}}
                                                 AND ra.{{QbAttempt.status}} <> 'IN_PROGRESS'
                                                 AND (pr.{{QbPublicationPrerequisite.requirement}} = 'ANSWERED'
                                                      OR ra.{{QbAttempt.passed}}))) THEN 'LOCKED'
                WHEN n.live THEN 'UNLOCKED'
                ELSE 'CLOSED' END AS state,
           CAST(NULL AS timestamptz) AS finished_at
    FROM customer c
    CROSS JOIN node n
    WHERE NOT EXISTS (SELECT 1 FROM {{QbAttempt}} a
                      WHERE a.{{QbAttempt.publicationId}} = n.id AND a.{{QbAttempt.userId}} = c.user_id)
)
SELECT
    CAST(md5(n.publisher_id || ':' || s.user_id) AS varchar(32)) AS userKey,
    u.{{QbProfile.displayName}}                                 AS userName,
    s.state                                                     AS state,
    s.finished_at                                               AS finishedAt,
    EXISTS (SELECT 1 FROM {{QbConversation}} cv
            WHERE cv.{{QbConversation.takerId}} = s.user_id
              AND cv.{{QbConversation.publicationId}} = n.id) AS hasConversation
FROM person s
CROSS JOIN node n
LEFT JOIN {{QbProfile}} u ON u.{{QbProfile.userId}} = s.user_id
WHERE s.state = :state
