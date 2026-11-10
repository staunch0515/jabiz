/*---
id: qb.public.taker-page
description: >-
  A taker's public certification page (/taker/u/<name>, Q77, D-Q77-5) as anybody sees it: the certificates of the nodes they
  lit that no revocation of the lighting ended and they share (F-S5a), and Q77's certificates they issued that no
  revocation ended, the latest of each certification, with the name on it and its key for the verify link; none when the
  page is off or the name unknown. The page leaves out Q77's whose validUntil passed. A Sponsor's certificates are on no
  public page.
access: public
cacheSeconds: 60
timeoutMs: 2000
entities: [QbUserSettings, QbCertificate, QbCertificationRecord, QbTreeLitRevocation, QbSponsorCertification, QbSponsor]
datasets:
  QbUserSettings: urn:jabiz:dataset:public:QbUserSettings
  QbCertificate: urn:jabiz:dataset:public:QbCertificate
  QbCertificationRecord: urn:jabiz:dataset:public:QbCertificationRecord
  QbTreeLitRevocation: urn:jabiz:dataset:public:QbTreeLitRevocation
  QbSponsorCertification: urn:jabiz:dataset:public:QbSponsorCertification
  QbSponsor: urn:jabiz:dataset:public:QbSponsor
params:
  handle: { like: QbUserSettings.publicHandle, required: true, description: The page's name (lowercase) }
results:
  handle:               { from: QbUserSettings.publicHandle }
  source:               { from: QbCertificate.source }
  certification:        { from: QbCertificate.certification }
  certificationVersion: { from: QbCertificate.certificationVersion }
  certificationNames:   { kind: { type: text } }
  certificateNames:     { kind: { type: text } }
  issuerName:           { kind: { type: text, maxLength: 200 } }
  holderName:           { from: QbCertificate.holderName }
  heldFrom:             { from: QbCertificate.heldFrom }
  validUntil:           { from: QbCertificate.validUntil }
  verifyKey:            { from: QbCertificate.verifyKey }
list:
  sorts: [heldFrom]
  defaultSort: { field: heldFrom, asc: false }
  key: [verifyKey]
---*/
-- The settings dataset holds the pages that are on only (its fixed scope). No user id leaves the template: the name on
-- the page is the one on the certificate (D-Q77-5: no profile is made public).
-- A node's certificates, newest first: the Taker's own (never a Sponsor's), shared, the lighting not revoked.
SELECT
    s.{{QbUserSettings.publicHandle}}        AS handle,
    n.{{QbCertificate.source}}               AS source,
    CAST(NULL AS varchar(40))                AS certification,
    CAST(NULL AS numeric)                    AS certificationVersion,
    CAST(NULL AS text)                       AS certificationNames,
    CAST(n.{{QbCertificate.certificateNames}} AS text) AS certificateNames,
    CAST(n.{{QbCertificate.issuerName}} AS varchar(200)) AS issuerName,
    n.{{QbCertificate.holderName}}           AS holderName,
    n.{{QbCertificate.heldFrom}}             AS heldFrom,
    CAST(NULL AS timestamptz)                AS validUntil,
    n.{{QbCertificate.verifyKey}}            AS verifyKey
FROM {{QbUserSettings}} s
JOIN {{QbCertificate}} n ON n.{{QbCertificate.userId}} = s.{{QbUserSettings.userId}}
    AND n.{{QbCertificate.source}} = 'NODE' AND n.{{QbCertificate.subjectKind}} = 'USER'
    AND n.{{QbCertificate.shared}}
    AND NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} v
                    WHERE v.{{QbTreeLitRevocation.litId}} = n.{{QbCertificate.litId}})
WHERE s.{{QbUserSettings.publicHandle}} = :handle
UNION ALL
SELECT
    s.{{QbUserSettings.publicHandle}}        AS handle,
    c.{{QbCertificate.source}}               AS source,
    c.{{QbCertificate.certification}}        AS certification,
    c.{{QbCertificate.certificationVersion}} AS certificationVersion,
    -- Q88a (D-Q88-6): a sponsor's certification is named by its row and issued by the sponsor; the catalog's
    -- names are the App's texts and their issuer the platform.
    CAST(sc.{{QbSponsorCertification.name}} AS text) AS certificationNames,
    CAST(NULL AS text)                       AS certificateNames,
    CAST(sp.{{QbSponsor.company}} AS varchar(200)) AS issuerName,
    c.{{QbCertificate.holderName}}           AS holderName,
    c.{{QbCertificate.heldFrom}}             AS heldFrom,
    c.{{QbCertificate.validUntil}}           AS validUntil,
    c.{{QbCertificate.verifyKey}}            AS verifyKey
FROM {{QbUserSettings}} s
JOIN LATERAL (
    SELECT DISTINCT ON (x.{{QbCertificate.certification}})
        x.{{QbCertificate.source}}, x.{{QbCertificate.certification}}, x.{{QbCertificate.certificationVersion}},
        x.{{QbCertificate.holderName}}, x.{{QbCertificate.heldFrom}}, x.{{QbCertificate.validUntil}},
        x.{{QbCertificate.verifyKey}}
    FROM {{QbCertificate}} x
    WHERE x.{{QbCertificate.userId}} = s.{{QbUserSettings.userId}}
      AND x.{{QbCertificate.source}} = 'CERTIFICATION'
      AND NOT EXISTS (SELECT 1 FROM {{QbCertificationRecord}} r
                      WHERE r.{{QbCertificationRecord.userId}} = x.{{QbCertificate.userId}}
                        AND r.{{QbCertificationRecord.certification}} = x.{{QbCertificate.certification}}
                        AND r.{{QbCertificationRecord.at}} > x.{{QbCertificate.heldFrom}})
    ORDER BY x.{{QbCertificate.certification}}, x.{{QbCertificate.validUntil}} DESC
) c ON true
LEFT JOIN {{QbSponsorCertification}} sc
    ON 'sp:' || sc.{{QbSponsorCertification.certificationId}} = c.{{QbCertificate.certification}}
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = sc.{{QbSponsorCertification.sponsorId}}
WHERE s.{{QbUserSettings.publicHandle}} = :handle
