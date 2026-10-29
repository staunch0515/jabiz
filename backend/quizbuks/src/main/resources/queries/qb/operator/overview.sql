/*---
id: qb.operator.overview
description: "The operator's home page (Q48a): its sponsors and the whole platform's sponsors and publications, in totals only"
entities: [QbSponsor, QbSponsorOperator, QbOperatorMember, QbPublicationCard, QbPublicationCounter, QbPublication]
datasets:
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbPublicationCounter: urn:jabiz:dataset:default:QbPublicationCounter
  QbPublication: urn:jabiz:dataset:default:QbPublication
results:
  mySponsors:          { kind: { type: numeric, precision: 9, scale: 0 } }
  sponsors:            { kind: { type: numeric, precision: 9, scale: 0 } }
  sponsorsInReview:    { kind: { type: numeric, precision: 9, scale: 0 } }
  publicationsInReview: { kind: { type: numeric, precision: 9, scale: 0 } }
  livePublications:    { kind: { type: numeric, precision: 9, scale: 0 } }
  myLivePublications:  { kind: { type: numeric, precision: 9, scale: 0 } }
  myCompletions:       { kind: { type: numeric, precision: 12, scale: 0 } }
permissions: [qb.operator.use]
---*/
-- Totals only (D-Q48-4: the takers' statistics in aggregate). The operator's own: its sponsors' (assigned to the
-- caller's operator), the completions of their publications on the cards (the completion side of the counters).
WITH me AS (
    SELECT m.{{QbOperatorMember.operatorId}} AS operator_id FROM {{QbOperatorMember}} m
     WHERE m.{{QbOperatorMember.removedAt}} IS NULL
), mine AS (
    SELECT a.{{QbSponsorOperator.userId}} AS user_id FROM {{QbSponsorOperator}} a
      JOIN me ON me.operator_id = a.{{QbSponsorOperator.operatorId}}
)
SELECT
    (SELECT count(*) FROM mine) AS mySponsors,
    (SELECT count(*) FROM {{QbSponsor}} s) AS sponsors,
    (SELECT count(*) FROM {{QbSponsor}} s WHERE s.{{QbSponsor.reviewStatus}} = 'PENDING') AS sponsorsInReview,
    (SELECT count(*) FROM {{QbPublication}} p
      WHERE p.{{QbPublication.publisherKind}} = 'SPONSOR'
        AND p.{{QbPublication.status}} = 'PENDING_REVIEW') AS publicationsInReview,
    (SELECT count(*) FROM {{QbPublicationCard}} c
      WHERE c.{{QbPublicationCard.publisherKind}} = 'SPONSOR'
        AND c.{{QbPublicationCard.status}} = 'LIVE') AS livePublications,
    (SELECT count(*) FROM {{QbPublicationCard}} c
      WHERE c.{{QbPublicationCard.publisherKind}} = 'SPONSOR' AND c.{{QbPublicationCard.status}} = 'LIVE'
        AND c.{{QbPublicationCard.publisherId}} IN (SELECT user_id FROM mine)) AS myLivePublications,
    (SELECT coalesce(sum(n.{{QbPublicationCounter.completions}}), 0) FROM {{QbPublicationCard}} c
      JOIN {{QbPublicationCounter}} n ON n.{{QbPublicationCounter.publicationId}} = c.{{QbPublicationCard.publicationId}}
      WHERE c.{{QbPublicationCard.publisherKind}} = 'SPONSOR'
        AND c.{{QbPublicationCard.publisherId}} IN (SELECT user_id FROM mine)) AS myCompletions
