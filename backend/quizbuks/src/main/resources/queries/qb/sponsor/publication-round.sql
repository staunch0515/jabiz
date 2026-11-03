/*---
id: qb.sponsor.publication-round
description: The certification a sponsor's publication is a round of (Q88a), if any, for the wizard and the publication's page
entities: [QbPublication, QbCertificationRound, QbSponsorCertification]
datasets:
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbCertificationRound: urn:jabiz:dataset:default:QbCertificationRound
  QbSponsorCertification: urn:jabiz:dataset:sponsor:QbSponsorCertification
params:
  publicationId: { like: QbPublication.publicationId, required: true, description: The publication }
results:
  roundId:              { from: QbCertificationRound.roundId }
  certification:        { from: QbCertificationRound.certification }
  certificationVersion: { from: QbCertificationRound.certificationVersion }
  addedAt:              { from: QbCertificationRound.addedAt }
  closedAt:             { from: QbCertificationRound.closedAt }
  certificationId:      { from: QbSponsorCertification.certificationId }
  name:                 { from: QbSponsorCertification.name }
  status:               { from: QbSponsorCertification.status }
  currentVersion:       { from: QbSponsorCertification.certificationVersion }
list:
  key: [roundId]
permissions: [qb.publication.write]
---*/
-- The sponsor's own publication only (its view holds the caller's): one row when it is a round, none otherwise. A
-- round of the catalog's certifications (an official publisher's, Q69) has no sponsor's row: its key says which.
SELECT
    r.{{QbCertificationRound.roundId}}              AS roundId,
    r.{{QbCertificationRound.certification}}        AS certification,
    r.{{QbCertificationRound.certificationVersion}} AS certificationVersion,
    r.{{QbCertificationRound.addedAt}}              AS addedAt,
    r.{{QbCertificationRound.closedAt}}             AS closedAt,
    sc.{{QbSponsorCertification.certificationId}}      AS certificationId,
    sc.{{QbSponsorCertification.name}}                 AS name,
    sc.{{QbSponsorCertification.status}}               AS status,
    sc.{{QbSponsorCertification.certificationVersion}} AS currentVersion
FROM {{QbPublication}} p
JOIN {{QbCertificationRound}} r ON r.{{QbCertificationRound.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
LEFT JOIN {{QbSponsorCertification}} sc
    ON 'sp:' || sc.{{QbSponsorCertification.certificationId}} = r.{{QbCertificationRound.certification}}
WHERE p.{{QbPublication.publicationId}} = :publicationId
