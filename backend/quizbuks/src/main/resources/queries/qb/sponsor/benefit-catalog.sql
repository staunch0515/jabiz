/*---
id: qb.sponsor.benefit-catalog
description: >-
  Q89a (D-Q89-3): the benefits sponsors list, for a sponsor holding a benefit certification (none otherwise),
  newest first; with the provider's company and the caller's conversation about each
entities: [QbBenefitOffer, QbBenefitConversation, QbSponsor, QbCertificationRound, QbCertificationRecord, QbAttempt]
datasets:
  QbBenefitOffer: urn:jabiz:dataset:default:QbBenefitOffer
  QbBenefitConversation: urn:jabiz:dataset:sponsor:QbBenefitConversation
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbCertificationRound: urn:jabiz:dataset:default:QbCertificationRound
  QbCertificationRecord: urn:jabiz:dataset:me:QbCertificationRecord
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
params:
  at:      { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time asked about (the client's now) }
  kind:    { kind: { type: text, maxLength: 10 }, description: "Only benefits of this kind" }
  q:       { kind: { type: text, maxLength: 100 }, description: "Only benefits whose name or provider contains this text" }
  offerId: { kind: { type: text, maxLength: 36 }, description: "Only this benefit (its page)" }
results:
  offerId:         { from: QbBenefitOffer.offerId }
  providerCompany: { from: QbSponsor.company }
  kind:            { from: QbBenefitOffer.kind }
  name:            { from: QbBenefitOffer.name }
  description:     { from: QbBenefitOffer.description }
  image1:          { from: QbBenefitOffer.image1 }
  image2:          { from: QbBenefitOffer.image2 }
  image3:          { from: QbBenefitOffer.image3 }
  image4:          { from: QbBenefitOffer.image4 }
  unitValue:       { from: QbBenefitOffer.unitValue }
  pickupAddress:   { from: QbBenefitOffer.pickupAddress }
  pickupHours:     { from: QbBenefitOffer.pickupHours }
  terms:           { from: QbBenefitOffer.terms }
  editedAt:        { from: QbBenefitOffer.editedAt }
  conversationId:  { from: QbBenefitConversation.conversationId }
list:
  filters: [kind]
  sorts:   [editedAt, name, unitValue]
  defaultSort: { field: editedAt, asc: false }
  key: [offerId]
permissions: [qb.benefit.use]
---*/
-- Only the listed benefits (the caller's own too: buying one's own is refused by the processes), and only while the caller holds either
-- certification (CertificationHolding's rule, as qb.sponsor.benefit-access; the catalog as Certifications writes it).
-- A provider whose onboarding is gone (an erased account) is not shown. The text is found as written: strpos takes
-- it literally.
WITH held AS (
    SELECT 1 AS yes
    FROM (VALUES ('SPONSOR_APPLY', 1, 31536000), ('REFERRER', 1, 31536000), ('TERMS_APP_UNDERSTOOD', 1, 31536000), ('CONTEST_RULES_UNDERSTOOD', 1, 31536000), ('TERMS_SPONSOR_UNDERSTOOD', 1, 31536000), ('BENEFIT_PROVIDER', 1, 31536000), ('BENEFIT_SPONSOR', 1, 31536000)) AS cat(cert, ver, secs)
    WHERE cat.cert IN ('BENEFIT_PROVIDER', 'BENEFIT_SPONSOR')
      AND (SELECT max(e.until)
     FROM (SELECT rec.{{QbCertificationRecord.certificationVersion}} AS ver, rec.{{QbCertificationRecord.at}} AS at,
                  rec.{{QbCertificationRecord.validUntil}} AS until
           FROM {{QbCertificationRecord}} rec
           WHERE rec.{{QbCertificationRecord.certification}} = cat.cert
             AND rec.{{QbCertificationRecord.kind}} IN ('PASSED', 'GRANTED')
           UNION ALL
           SELECT r2.{{QbCertificationRound.certificationVersion}}, a.{{QbAttempt.submittedAt}},
                  a.{{QbAttempt.submittedAt}} + make_interval(secs => cat.secs)
           FROM {{QbCertificationRound}} r2
           JOIN {{QbAttempt}} a ON CAST(a.{{QbAttempt.publicationId}} AS text) = r2.{{QbCertificationRound.publicationId}}
           WHERE r2.{{QbCertificationRound.certification}} = cat.cert
             AND a.{{QbAttempt.passed}} AND a.{{QbAttempt.submittedAt}} IS NOT NULL
             AND (r2.{{QbCertificationRound.closedAt}} IS NULL
                  OR a.{{QbAttempt.submittedAt}} < r2.{{QbCertificationRound.closedAt}})) e
     WHERE e.ver = cat.ver AND e.until > CAST(:at AS timestamptz)
       AND e.at > coalesce((SELECT max(rv.{{QbCertificationRecord.at}}) FROM {{QbCertificationRecord}} rv
                            WHERE rv.{{QbCertificationRecord.certification}} = cat.cert
                              AND rv.{{QbCertificationRecord.kind}} = 'REVOKED'), '-infinity')) IS NOT NULL
    LIMIT 1
)
SELECT
    o.{{QbBenefitOffer.offerId}}       AS offerId,
    sp.{{QbSponsor.company}}           AS providerCompany,
    o.{{QbBenefitOffer.kind}}          AS kind,
    o.{{QbBenefitOffer.name}}          AS name,
    o.{{QbBenefitOffer.description}}   AS description,
    o.{{QbBenefitOffer.image1}}        AS image1,
    o.{{QbBenefitOffer.image2}}        AS image2,
    o.{{QbBenefitOffer.image3}}        AS image3,
    o.{{QbBenefitOffer.image4}}        AS image4,
    o.{{QbBenefitOffer.unitValue}}     AS unitValue,
    o.{{QbBenefitOffer.pickupAddress}} AS pickupAddress,
    o.{{QbBenefitOffer.pickupHours}}   AS pickupHours,
    o.{{QbBenefitOffer.terms}}         AS terms,
    o.{{QbBenefitOffer.editedAt}}      AS editedAt,
    c.{{QbBenefitConversation.conversationId}} AS conversationId
FROM {{QbBenefitOffer}} o
JOIN held ON true
JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = o.{{QbBenefitOffer.providerId}}
LEFT JOIN {{QbBenefitConversation}} c ON c.{{QbBenefitConversation.offerId}} = o.{{QbBenefitOffer.offerId}}
WHERE o.{{QbBenefitOffer.status}} = 'LISTED'
  AND (CAST(:kind AS text) IS NULL OR o.{{QbBenefitOffer.kind}} = CAST(:kind AS text))
  AND (CAST(:offerId AS text) IS NULL OR o.{{QbBenefitOffer.offerId}} = CAST(:offerId AS text))
  AND (CAST(:q AS text) IS NULL OR strpos(lower(o.{{QbBenefitOffer.name}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(sp.{{QbSponsor.company}}, '')), lower(CAST(:q AS text))) > 0)
