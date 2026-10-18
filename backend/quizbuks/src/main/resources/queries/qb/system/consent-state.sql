/*---
id: qb.system.consent-state
description: >-
  For each kind of legal text a user is asked to accept, the version in effect, the version to accept and the highest
  version the user accepted (the server's check before money and contract actions, Q7b D-Q7-5)
entities: [QbLegalVersion, QbConsent]
datasets:
  QbLegalVersion: urn:jabiz:dataset:default:QbLegalVersion
  QbConsent: urn:jabiz:dataset:default:QbConsent
params:
  userId: { kind: { type: text, maxLength: 128 }, required: true, description: The user }
  kinds:  { like: QbLegalVersion.kind, list: true, required: true, description: The kinds of text asked for }
  at:     { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The time (the process's)" }
results:
  kind:             { from: QbLegalVersion.kind }
  currentVersionId: { from: QbLegalVersion.legalVersionId }
  currentVersionNo: { from: QbLegalVersion.versionNumber }
  toAccept:         { kind: { type: numeric, precision: 6, scale: 0 } }
  accepted:         { kind: { type: numeric, precision: 6, scale: 0 } }
permissions: [qb.messaging.system]
---*/
-- The rules of messaging.LegalRules: in effect is the highest number taking effect at or before :at; to accept is the
-- highest such number that requires consent (the first always does); accepted is the user's highest. A kind without
-- a version in effect has no row (nothing is asked). One statement, outside any lock.
WITH effective AS (
    SELECT
        v.{{QbLegalVersion.kind}}            AS kind,
        v.{{QbLegalVersion.legalVersionId}}  AS id,
        v.{{QbLegalVersion.versionNumber}}   AS no,
        v.{{QbLegalVersion.requiresConsent}} AS rc
    FROM {{QbLegalVersion}} v
    WHERE v.{{QbLegalVersion.kind}} = ANY(:kinds)
      AND v.{{QbLegalVersion.effectiveAt}} <= :at),
by_kind AS (
    SELECT kind, max(no) AS current_no, max(no) FILTER (WHERE rc OR no = 1) AS to_accept
    FROM effective GROUP BY kind)
SELECT
    e.kind      AS kind,
    e.id        AS currentVersionId,
    e.no        AS currentVersionNo,
    b.to_accept AS toAccept,
    (SELECT max(c.{{QbConsent.versionNumber}}) FROM {{QbConsent}} c
     WHERE c.{{QbConsent.userId}} = CAST(:userId AS text) AND c.{{QbConsent.kind}} = e.kind) AS accepted
FROM effective e
JOIN by_kind b ON b.kind = e.kind AND b.current_no = e.no
