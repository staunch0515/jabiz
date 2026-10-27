/*---
id: qb.app.staff-redemptions
description: "The prizes of the sponsor whose staff the taker is on (Q39), newest first: the claims to answer and what was collected"
entities: [QbSponsorStaff, QbRedemption, QbPublicationCard, QbProfile]
datasets:
  QbSponsorStaff: urn:jabiz:dataset:taker:QbSponsorStaff
  QbRedemption: urn:jabiz:dataset:default:QbRedemption
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbProfile: urn:jabiz:dataset:default:QbProfile
params:
  since: { like: QbRedemption.dueAt, description: "Only prizes claimed or collected at or after this time (the day's list)" }
results:
  redemptionId:    { from: QbRedemption.redemptionId }
  code:            { from: QbRedemption.code }
  publicationName: { from: QbPublicationCard.name }
  takerName:       { from: QbProfile.displayName }
  description:     { from: QbRedemption.description }
  status:          { from: QbRedemption.status }
  claimedAt:       { from: QbRedemption.claimedAt }
  confirmBy:       { from: QbRedemption.confirmBy }
  redeemedAt:      { from: QbRedemption.redeemedAt }
  redeemedByName:  { from: QbProfile.displayName }
list:
  filters: [status, claimedAt, redeemedAt]
  sorts:   [claimedAt, redeemedAt]
  defaultSort: { field: claimedAt, asc: false }
  key: [redemptionId]
permissions: [qb.me]
---*/
-- The staff dataset is the caller's own place (none: no rows); the sponsor's prizes are read through it. Only prizes
-- claimed or collected: the open ones are the takers' business until they scan. Who handed a prize over is named when
-- a staff member did (none when the sponsor did).
SELECT
    r.{{QbRedemption.redemptionId}}  AS redemptionId,
    r.{{QbRedemption.code}}          AS code,
    c.{{QbPublicationCard.name}}     AS publicationName,
    p.{{QbProfile.displayName}}      AS takerName,
    r.{{QbRedemption.description}}   AS description,
    r.{{QbRedemption.status}}        AS status,
    r.{{QbRedemption.claimedAt}}     AS claimedAt,
    r.{{QbRedemption.confirmBy}}     AS confirmBy,
    r.{{QbRedemption.redeemedAt}}    AS redeemedAt,
    b.{{QbProfile.displayName}}      AS redeemedByName
FROM {{QbSponsorStaff}} s
JOIN {{QbRedemption}} r ON r.{{QbRedemption.publisherKind}} = 'SPONSOR'
                       AND r.{{QbRedemption.publisherId}} = s.{{QbSponsorStaff.sponsorId}}
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = r.{{QbRedemption.publicationId}}
LEFT JOIN {{QbProfile}} p ON p.{{QbProfile.userId}} = r.{{QbRedemption.userId}}
LEFT JOIN {{QbProfile}} b ON b.{{QbProfile.userId}} = r.{{QbRedemption.redeemedBy}}
                         AND r.{{QbRedemption.redeemedBy}} <> r.{{QbRedemption.publisherId}}
WHERE r.{{QbRedemption.claimedAt}} IS NOT NULL
  AND (CAST(:since AS timestamptz) IS NULL
       OR r.{{QbRedemption.claimedAt}} >= :since OR r.{{QbRedemption.redeemedAt}} >= :since)
