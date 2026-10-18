/*---
id: qb.public.legal-history
description: Every published version of a kind of legal text, newest first - its number, title, effective time, summary of changes and whether it was withdrawn before taking effect
access: public
cacheSeconds: 300
timeoutMs: 2000
entities: [QbLegalVersion, QbLegalWithdrawal]
datasets:
  QbLegalVersion: urn:jabiz:dataset:public:QbLegalVersion
  QbLegalWithdrawal: urn:jabiz:dataset:public:QbLegalWithdrawal
params:
  kind: { like: QbLegalVersion.kind, required: true, description: The kind of text }
results:
  versionNo:       { from: QbLegalVersion.versionNumber }
  title:           { from: QbLegalVersion.title }
  changeSummary:   { from: QbLegalVersion.changeSummary }
  requiresConsent: { from: QbLegalVersion.requiresConsent }
  effectiveAt:     { from: QbLegalVersion.effectiveAt }
  withdrawn:       { kind: { type: bool } }
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
    p.{{QbLegalVersion.effectiveAt}}     AS effectiveAt,
    EXISTS (SELECT 1 FROM {{QbLegalWithdrawal}} w
            WHERE w.{{QbLegalWithdrawal.kind}} = p.{{QbLegalVersion.kind}}
              AND w.{{QbLegalWithdrawal.versionNumber}} = p.{{QbLegalVersion.versionNumber}}) AS withdrawn
FROM {{QbLegalVersion}} p
WHERE p.{{QbLegalVersion.kind}} = :kind
