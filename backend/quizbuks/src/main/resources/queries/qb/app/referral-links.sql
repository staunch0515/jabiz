/*---
id: qb.app.referral-links
description: "The referrer's own links (Q70): the quiz, its referral reward and the places left, newest first"
entities: [QbReferralLink, QbReferral, QbPublicationCard]
datasets:
  QbReferralLink: urn:jabiz:dataset:referrer:QbReferralLink
  QbReferral: urn:jabiz:dataset:default:QbReferral
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
results:
  linkId:          { from: QbReferralLink.linkId }
  linkKey:         { from: QbReferralLink.linkKey }
  publicationId:   { from: QbReferralLink.publicationId }
  publicationName: { from: QbPublicationCard.name }
  referral:        { from: QbPublicationCard.referral }
  status:          { from: QbPublicationCard.status }
  endAt:           { from: QbPublicationCard.endAt }
  createdAt:       { from: QbReferralLink.createdAt }
  placesLeft:      { kind: { type: numeric, precision: 5, scale: 0 } }
  broughtByMe:     { kind: { type: numeric, precision: 5, scale: 0 } }
list:
  sorts:   [createdAt]
  defaultSort: { field: createdAt, asc: false }
  key: [linkId]
permissions: [qb.me]
---*/
-- The places left: the quota less the publication's referrals that hold one (referred, rewarded); how many the caller
-- brought, of every status (the limit of D-Q70-19b counts them all).
SELECT
    l.{{QbReferralLink.linkId}}        AS linkId,
    l.{{QbReferralLink.linkKey}}       AS linkKey,
    l.{{QbReferralLink.publicationId}} AS publicationId,
    c.{{QbPublicationCard.name}}       AS publicationName,
    c.{{QbPublicationCard.referral}}   AS referral,
    c.{{QbPublicationCard.status}}     AS status,
    c.{{QbPublicationCard.endAt}}      AS endAt,
    l.{{QbReferralLink.createdAt}}     AS createdAt,
    CASE WHEN c.{{QbPublicationCard.referral}} IS NULL THEN 0
         ELSE greatest(0, CAST(CAST(c.{{QbPublicationCard.referral}} AS jsonb) ->> 'quota' AS int)
             - (SELECT count(*) FROM {{QbReferral}} q
                WHERE q.{{QbReferral.publicationId}} = l.{{QbReferralLink.publicationId}}
                  AND q.{{QbReferral.status}} IN ('REFERRED', 'REWARDED'))) END AS placesLeft,
    (SELECT count(*) FROM {{QbReferral}} q
     WHERE q.{{QbReferral.linkId}} = l.{{QbReferralLink.linkId}}) AS broughtByMe
FROM {{QbReferralLink}} l
LEFT JOIN {{QbPublicationCard}} c
    ON CAST(c.{{QbPublicationCard.publicationId}} AS text) = l.{{QbReferralLink.publicationId}}
