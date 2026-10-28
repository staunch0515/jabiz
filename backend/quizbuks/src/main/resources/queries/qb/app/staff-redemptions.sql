/*---
id: qb.app.staff-redemptions
description: "At the counter of the sponsor whose staff the taker is on (Q39), newest first: the claims to answer and the prizes the taker handed over"
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
list:
  filters: [status, claimedAt, redeemedAt]
  sorts:   [claimedAt, redeemedAt]
  defaultSort: { field: claimedAt, asc: false }
  key: [redemptionId]
permissions: [qb.me]
---*/
-- The staff dataset is the caller's own place (none: no rows, and s.userId is the caller); the sponsor's prizes are read
-- through it. A staff member sees what the counter needs (D-Q39-3): the claims waiting for an answer, and the prizes they
-- handed over themselves; not the sponsor's history, nor what others handed over.
SELECT
    r.{{QbRedemption.redemptionId}}  AS redemptionId,
    r.{{QbRedemption.code}}          AS code,
    c.{{QbPublicationCard.name}}     AS publicationName,
    p.{{QbProfile.displayName}}      AS takerName,
    r.{{QbRedemption.description}}   AS description,
    r.{{QbRedemption.status}}        AS status,
    r.{{QbRedemption.claimedAt}}     AS claimedAt,
    r.{{QbRedemption.confirmBy}}     AS confirmBy,
    r.{{QbRedemption.redeemedAt}}    AS redeemedAt
FROM {{QbSponsorStaff}} s
JOIN {{QbRedemption}} r ON r.{{QbRedemption.publisherKind}} = 'SPONSOR'
                       AND r.{{QbRedemption.publisherId}} = s.{{QbSponsorStaff.sponsorId}}
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = r.{{QbRedemption.publicationId}}
LEFT JOIN {{QbProfile}} p ON p.{{QbProfile.userId}} = r.{{QbRedemption.userId}}
WHERE (r.{{QbRedemption.status}} = 'CLAIMED' OR r.{{QbRedemption.redeemedBy}} = s.{{QbSponsorStaff.userId}})
  AND (CAST(:since AS timestamptz) IS NULL
       OR r.{{QbRedemption.claimedAt}} >= :since OR r.{{QbRedemption.redeemedAt}} >= :since)
