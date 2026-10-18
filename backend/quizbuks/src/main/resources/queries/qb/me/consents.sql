/*---
id: qb.me.consents
description: >-
  The caller's standing with each kind of legal text their front end asks for (Q7b D-Q7-5): the version in effect,
  its title and summary of changes, the version to accept, the caller's highest accepted and whether they must accept
  again
entities: [QbLegalVersion, QbConsent]
datasets:
  QbLegalVersion: urn:jabiz:dataset:default:QbLegalVersion
  QbConsent: urn:jabiz:dataset:me:QbConsent
params:
  kinds: { like: QbLegalVersion.kind, list: true, required: true, description: "The kinds of text (the app TERMS_APP and PRIVACY, the sponsor console TERMS_SPONSOR and PRIVACY)" }
  at:    { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The client's now" }
results:
  kind:            { from: QbLegalVersion.kind }
  versionId:       { from: QbLegalVersion.legalVersionId }
  versionNo:       { from: QbLegalVersion.versionNumber }
  title:           { from: QbLegalVersion.title }
  changeSummary:   { from: QbLegalVersion.changeSummary }
  requiresConsent: { from: QbLegalVersion.requiresConsent }
  effectiveAt:     { from: QbLegalVersion.effectiveAt }
  toAccept:        { kind: { type: numeric, precision: 6, scale: 0 } }
  accepted:        { kind: { type: numeric, precision: 6, scale: 0 } }
  mustAccept:      { kind: { type: bool } }
list:
  sorts: [kind]
  defaultSort: { field: kind, asc: true }
  key: [kind]
permissions: [qb.account.read]
---*/
-- The same rules as qb.system.consent-state (messaging.LegalRules), for the pages; the server decides with its own
-- clock. The caller's consents only (the dataset's scope). A kind without a version in effect has no row.
WITH effective AS (
    SELECT
        v.{{QbLegalVersion.kind}}            AS kind,
        v.{{QbLegalVersion.legalVersionId}}  AS id,
        v.{{QbLegalVersion.versionNumber}}   AS no,
        v.{{QbLegalVersion.title}}           AS title,
        v.{{QbLegalVersion.changeSummary}}   AS change_summary,
        v.{{QbLegalVersion.requiresConsent}} AS rc,
        v.{{QbLegalVersion.effectiveAt}}     AS effective_at
    FROM {{QbLegalVersion}} v
    WHERE v.{{QbLegalVersion.kind}} = ANY(:kinds)
      AND v.{{QbLegalVersion.effectiveAt}} <= :at),
by_kind AS (
    SELECT kind, max(no) AS current_no, max(no) FILTER (WHERE rc OR no = 1) AS to_accept
    FROM effective GROUP BY kind),
mine AS (
    SELECT c.{{QbConsent.kind}} AS kind, max(c.{{QbConsent.versionNumber}}) AS accepted
    FROM {{QbConsent}} c
    GROUP BY c.{{QbConsent.kind}})
SELECT
    e.kind           AS kind,
    e.id             AS versionId,
    e.no             AS versionNo,
    e.title          AS title,
    e.change_summary AS changeSummary,
    e.rc             AS requiresConsent,
    e.effective_at   AS effectiveAt,
    b.to_accept      AS toAccept,
    m.accepted       AS accepted,
    coalesce(m.accepted, 0) < b.to_accept AS mustAccept
FROM effective e
JOIN by_kind b ON b.kind = e.kind AND b.current_no = e.no
LEFT JOIN mine m ON m.kind = e.kind
