/*---
id: qb.admin.consents
description: A user's acceptances of the terms and the privacy policy (P-01 user summary, Q7b), newest first
entities: [QbConsent, QbLegalVersion]
datasets:
  QbConsent: urn:jabiz:dataset:default:QbConsent
  QbLegalVersion: urn:jabiz:dataset:default:QbLegalVersion
params:
  userId: { like: QbConsent.userId, required: true, description: The user }
results:
  consentId:   { from: QbConsent.consentId }
  kind:        { from: QbConsent.kind }
  versionNo:   { from: QbConsent.versionNumber }
  acceptedAt:  { from: QbConsent.acceptedAt }
  entry:       { from: QbConsent.entry }
  source:      { from: QbConsent.source }
  effectiveAt: { from: QbLegalVersion.effectiveAt }
list:
  filters: [kind, source, acceptedAt]
  sorts:   [acceptedAt, kind]
  defaultSort: { field: acceptedAt, asc: false }
  key: [consentId]
permissions: [qb.admin.users.read]
---*/
-- The user is the indexed lookup; the version gives when it took effect.
SELECT
    c.{{QbConsent.consentId}}        AS consentId,
    c.{{QbConsent.kind}}             AS kind,
    c.{{QbConsent.versionNumber}}    AS versionNo,
    c.{{QbConsent.acceptedAt}}       AS acceptedAt,
    c.{{QbConsent.entry}}            AS entry,
    c.{{QbConsent.source}}           AS source,
    v.{{QbLegalVersion.effectiveAt}} AS effectiveAt
FROM {{QbConsent}} c
LEFT JOIN {{QbLegalVersion}} v ON v.{{QbLegalVersion.legalVersionId}} = c.{{QbConsent.legalVersionId}}
WHERE c.{{QbConsent.userId}} = :userId
