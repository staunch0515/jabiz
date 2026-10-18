/*---
id: qb.public.legal-version
description: One published version of a kind of legal text, in full (the history's "view" and an announced version)
access: public
cacheSeconds: 300
timeoutMs: 2000
entities: [QbLegalVersion, QbLegalWithdrawal]
datasets:
  QbLegalVersion: urn:jabiz:dataset:public:QbLegalVersion
  QbLegalWithdrawal: urn:jabiz:dataset:public:QbLegalWithdrawal
params:
  kind:      { like: QbLegalVersion.kind, required: true, description: The kind of text }
  versionNo: { like: QbLegalVersion.versionNumber, required: true, description: The version's number }
results:
  kind:            { from: QbLegalVersion.kind }
  versionNo:       { from: QbLegalVersion.versionNumber }
  title:           { from: QbLegalVersion.title }
  body:            { from: QbLegalVersion.body }
  changeSummary:   { from: QbLegalVersion.changeSummary }
  requiresConsent: { from: QbLegalVersion.requiresConsent }
  effectiveAt:     { from: QbLegalVersion.effectiveAt }
  withdrawn:       { kind: { type: bool } }
list:
  key: [kind, versionNo]
---*/
-- A version announced for later is public from its publication: announcing a change is publishing its text with the
-- time it takes effect (Q7-6); withdrawing it before then is said too. Drafts are never here.
SELECT
    p.{{QbLegalVersion.kind}}            AS kind,
    p.{{QbLegalVersion.versionNumber}}   AS versionNo,
    p.{{QbLegalVersion.title}}           AS title,
    p.{{QbLegalVersion.body}}            AS body,
    p.{{QbLegalVersion.changeSummary}}   AS changeSummary,
    p.{{QbLegalVersion.requiresConsent}} AS requiresConsent,
    p.{{QbLegalVersion.effectiveAt}}     AS effectiveAt,
    EXISTS (SELECT 1 FROM {{QbLegalWithdrawal}} w
            WHERE w.{{QbLegalWithdrawal.kind}} = p.{{QbLegalVersion.kind}}
              AND w.{{QbLegalWithdrawal.versionNumber}} = p.{{QbLegalVersion.versionNumber}}) AS withdrawn
FROM {{QbLegalVersion}} p
WHERE p.{{QbLegalVersion.kind}} = :kind
  AND p.{{QbLegalVersion.versionNumber}} = :versionNo
