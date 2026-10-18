/*---
id: qb.admin.legal-versions
description: >-
  The published versions of the legal texts for the content administrators (Q7b review M1): when each takes effect,
  who published it, and whether it was withdrawn (when, by whom and why), newest first
entities: [QbLegalVersion, QbLegalWithdrawal]
datasets:
  QbLegalVersion: urn:jabiz:dataset:default:QbLegalVersion
  QbLegalWithdrawal: urn:jabiz:dataset:default:QbLegalWithdrawal
results:
  legalVersionId:  { from: QbLegalVersion.legalVersionId }
  kind:            { from: QbLegalVersion.kind }
  versionNo:       { from: QbLegalVersion.versionNumber }
  title:           { from: QbLegalVersion.title }
  requiresConsent: { from: QbLegalVersion.requiresConsent }
  effectiveAt:     { from: QbLegalVersion.effectiveAt }
  publishedAt:     { from: QbLegalVersion.publishedAt }
  publishedBy:     { from: QbLegalVersion.publishedBy }
  withdrawn:       { kind: { type: bool } }
  withdrawnAt:     { from: QbLegalWithdrawal.withdrawnAt }
  withdrawnBy:     { from: QbLegalWithdrawal.withdrawnBy }
  reason:          { from: QbLegalWithdrawal.reason }
list:
  filters: [kind]
  sorts:   [versionNo, effectiveAt, kind]
  defaultSort: { field: versionNo, asc: false }
  key: [legalVersionId]
permissions: [qb.admin.content.write]
---*/
-- Few rows (a version per change of a text); the withdrawal, if any, by the version's key.
SELECT
    v.{{QbLegalVersion.legalVersionId}}  AS legalVersionId,
    v.{{QbLegalVersion.kind}}            AS kind,
    v.{{QbLegalVersion.versionNumber}}   AS versionNo,
    v.{{QbLegalVersion.title}}           AS title,
    v.{{QbLegalVersion.requiresConsent}} AS requiresConsent,
    v.{{QbLegalVersion.effectiveAt}}     AS effectiveAt,
    v.{{QbLegalVersion.publishedAt}}     AS publishedAt,
    v.{{QbLegalVersion.publishedBy}}     AS publishedBy,
    w.{{QbLegalWithdrawal.legalVersionId}} IS NOT NULL AS withdrawn,
    w.{{QbLegalWithdrawal.withdrawnAt}}  AS withdrawnAt,
    w.{{QbLegalWithdrawal.withdrawnBy}}  AS withdrawnBy,
    w.{{QbLegalWithdrawal.reason}}       AS reason
FROM {{QbLegalVersion}} v
LEFT JOIN {{QbLegalWithdrawal}} w
       ON w.{{QbLegalWithdrawal.legalVersionId}} = v.{{QbLegalVersion.legalVersionId}}
