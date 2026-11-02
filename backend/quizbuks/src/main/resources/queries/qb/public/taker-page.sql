/*---
id: qb.public.taker-page
description: >-
  A taker's public certification page (/taker/u/<name>, Q77, D-Q77-5) as anybody sees it: the certificates they issued
  that no revocation ended, the latest of each certification, with the name on it and its key for the verify link;
  none when the page is off or the name unknown. The page leaves out the ones whose validUntil passed.
access: public
cacheSeconds: 60
timeoutMs: 2000
entities: [QbUserSettings, QbCertificate, QbCertificationRecord]
datasets:
  QbUserSettings: urn:jabiz:dataset:public:QbUserSettings
  QbCertificate: urn:jabiz:dataset:public:QbCertificate
  QbCertificationRecord: urn:jabiz:dataset:public:QbCertificationRecord
params:
  handle: { like: QbUserSettings.publicHandle, required: true, description: The page's name (lowercase) }
results:
  handle:               { from: QbUserSettings.publicHandle }
  certification:        { from: QbCertificate.certification }
  certificationVersion: { from: QbCertificate.certificationVersion }
  holderName:           { from: QbCertificate.holderName }
  heldFrom:             { from: QbCertificate.heldFrom }
  validUntil:           { from: QbCertificate.validUntil }
  verifyKey:            { from: QbCertificate.verifyKey }
list:
  key: [certification]
---*/
-- The settings dataset holds the pages that are on only (its fixed scope). No user id leaves the template: the name on
-- the page is the one on the latest certificate (D-Q77-5: no profile is made public).
SELECT
    s.{{QbUserSettings.publicHandle}}        AS handle,
    c.{{QbCertificate.certification}}        AS certification,
    c.{{QbCertificate.certificationVersion}} AS certificationVersion,
    c.{{QbCertificate.holderName}}           AS holderName,
    c.{{QbCertificate.heldFrom}}             AS heldFrom,
    c.{{QbCertificate.validUntil}}           AS validUntil,
    c.{{QbCertificate.verifyKey}}            AS verifyKey
FROM {{QbUserSettings}} s
JOIN LATERAL (
    SELECT DISTINCT ON (x.{{QbCertificate.certification}})
        x.{{QbCertificate.certification}}, x.{{QbCertificate.certificationVersion}}, x.{{QbCertificate.holderName}},
        x.{{QbCertificate.heldFrom}}, x.{{QbCertificate.validUntil}}, x.{{QbCertificate.verifyKey}}
    FROM {{QbCertificate}} x
    WHERE x.{{QbCertificate.userId}} = s.{{QbUserSettings.userId}}
      AND NOT EXISTS (SELECT 1 FROM {{QbCertificationRecord}} r
                      WHERE r.{{QbCertificationRecord.userId}} = x.{{QbCertificate.userId}}
                        AND r.{{QbCertificationRecord.certification}} = x.{{QbCertificate.certification}}
                        AND r.{{QbCertificationRecord.at}} > x.{{QbCertificate.heldFrom}})
    ORDER BY x.{{QbCertificate.certification}}, x.{{QbCertificate.validUntil}} DESC
) c ON true
WHERE s.{{QbUserSettings.publicHandle}} = :handle
