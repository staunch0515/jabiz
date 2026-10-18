/*---
id: qb.public.legal
description: >-
  A kind of legal text as anybody reads it (U-73, P-05; Q7b D-Q7-6): the version in effect at a time, with the next
  version announced for later (its number and effective time), if any
access: public
cacheSeconds: 300
timeoutMs: 2000
entities: [QbLegalVersion]
datasets:
  QbLegalVersion: urn:jabiz:dataset:public:QbLegalVersion
params:
  kind: { like: QbLegalVersion.kind, required: true, description: "TERMS_APP, TERMS_SPONSOR, PRIVACY or COMMERCE_NOTICE" }
  at:   { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The time (the page's now, to the minute)" }
results:
  kind:                { from: QbLegalVersion.kind }
  versionNo:           { from: QbLegalVersion.versionNumber }
  title:               { from: QbLegalVersion.title }
  body:                { from: QbLegalVersion.body }
  changeSummary:       { from: QbLegalVersion.changeSummary }
  requiresConsent:     { from: QbLegalVersion.requiresConsent }
  effectiveAt:         { from: QbLegalVersion.effectiveAt }
  upcomingVersionNo:   { from: QbLegalVersion.versionNumber }
  upcomingEffectiveAt: { from: QbLegalVersion.effectiveAt }
list:
  key: [kind]
---*/
-- Only published versions exist in the public dataset (the drafts are another entity with no public dataset). In
-- effect is the highest number taking effect at or before :at (messaging.LegalRules); the announced one the lowest
-- number taking effect after it. One row while either exists; the version's fields are null before the first takes
-- effect.
WITH v AS (
    SELECT
        p.{{QbLegalVersion.kind}}            AS kind,
        p.{{QbLegalVersion.versionNumber}}   AS no,
        p.{{QbLegalVersion.title}}           AS title,
        p.{{QbLegalVersion.body}}            AS body,
        p.{{QbLegalVersion.changeSummary}}   AS change_summary,
        p.{{QbLegalVersion.requiresConsent}} AS rc,
        p.{{QbLegalVersion.effectiveAt}}     AS effective_at
    FROM {{QbLegalVersion}} p
    WHERE p.{{QbLegalVersion.kind}} = :kind),
cur AS (
    SELECT * FROM v WHERE v.effective_at <= :at
    AND v.no = (SELECT max(w.no) FROM v w WHERE w.effective_at <= :at)),
nxt AS (
    SELECT * FROM v WHERE v.effective_at > :at
    AND v.no = (SELECT min(w.no) FROM v w WHERE w.effective_at > :at))
SELECT
    CAST(:kind AS text)  AS kind,
    cur.no               AS versionNo,
    cur.title            AS title,
    cur.body             AS body,
    cur.change_summary   AS changeSummary,
    cur.rc               AS requiresConsent,
    cur.effective_at     AS effectiveAt,
    nxt.no               AS upcomingVersionNo,
    nxt.effective_at     AS upcomingEffectiveAt
FROM (SELECT 1) one
LEFT JOIN cur ON true
LEFT JOIN nxt ON true
WHERE cur.no IS NOT NULL OR nxt.no IS NOT NULL
