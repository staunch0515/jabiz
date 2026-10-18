/*---
id: qb.public.legal-history
description: Every published version of a kind of legal text, newest first - its number, title, effective time and summary of changes
access: public
cacheSeconds: 300
timeoutMs: 2000
entities: [QbLegalVersion]
datasets:
  QbLegalVersion: urn:jabiz:dataset:public:QbLegalVersion
params:
  kind: { like: QbLegalVersion.kind, required: true, description: The kind of text }
results:
  versionNo:       { from: QbLegalVersion.versionNumber }
  title:           { from: QbLegalVersion.title }
  changeSummary:   { from: QbLegalVersion.changeSummary }
  requiresConsent: { from: QbLegalVersion.requiresConsent }
  effectiveAt:     { from: QbLegalVersion.effectiveAt }
list:
  sorts: [versionNo]
  defaultSort: { field: versionNo, asc: false }
  key: [versionNo]
---*/
SELECT
    p.{{QbLegalVersion.versionNumber}}   AS versionNo,
    p.{{QbLegalVersion.title}}           AS title,
    p.{{QbLegalVersion.changeSummary}}   AS changeSummary,
    p.{{QbLegalVersion.requiresConsent}} AS requiresConsent,
    p.{{QbLegalVersion.effectiveAt}}     AS effectiveAt
FROM {{QbLegalVersion}} p
WHERE p.{{QbLegalVersion.kind}} = :kind
