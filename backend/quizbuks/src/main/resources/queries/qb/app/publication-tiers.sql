/*---
id: qb.app.publication-tiers
description: The reward tiers of a publication a taker can see (U-21), lowest first
entities: [QbPublicationCard, QbCountry, QbAttempt]
datasets:
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbCountry: urn:jabiz:dataset:default:QbCountry
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
params:
  publicationId: { like: QbPublicationCard.publicationId, required: true, description: The publication }
  at:            { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time the page is for (the client's now) }
  country:       { kind: { type: text, maxLength: 2 }, description: "The taker's country (QbCountry.code)" }
results:
  seq:       { kind: { type: numeric, precision: 2, scale: 0 } }
  fromScore: { kind: { type: numeric, precision: 6, scale: 0 } }
  toScore:   { kind: { type: numeric, precision: 6, scale: 0 } }
  reward:    { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [seq]
  defaultSort: { field: seq, asc: true }
  key: [seq]
permissions: [qb.play]
---*/
-- The tiers the card keeps (publishing.CardTiers: frozen at submission, in their order), when qb.app.publication shows
-- the publication: live, within its time and aimed at the taker's region, or the caller has an attempt of it.
SELECT
    CAST(t.seq AS numeric(2, 0))                        AS seq,
    CAST(t.tier ->> 'fromScore' AS numeric(6, 0))       AS fromScore,
    CAST(t.tier ->> 'toScore' AS numeric(6, 0))         AS toScore,
    CAST(t.tier ->> 'reward' AS numeric(19, 0))         AS reward
FROM {{QbPublicationCard}} c
LEFT JOIN {{QbAttempt}} m ON m.{{QbAttempt.publicationId}} = c.{{QbPublicationCard.publicationId}}
CROSS JOIN LATERAL jsonb_array_elements(CAST(c.{{QbPublicationCard.tiers}} AS jsonb)) WITH ORDINALITY AS t(tier, seq)
WHERE c.{{QbPublicationCard.publicationId}} = :publicationId
  AND (m.{{QbAttempt.attemptId}} IS NOT NULL
       OR (c.{{QbPublicationCard.status}} = 'LIVE'
           AND c.{{QbPublicationCard.startAt}} <= :at AND :at < c.{{QbPublicationCard.endAt}}
           AND string_to_array(c.{{QbPublicationCard.regions}}, ',') && string_to_array(coalesce(
               (SELECT co.{{QbCountry.regions}} FROM {{QbCountry}} co
                WHERE co.{{QbCountry.code}} = CAST(:country AS text)), 'GLOBAL'), ',')))
