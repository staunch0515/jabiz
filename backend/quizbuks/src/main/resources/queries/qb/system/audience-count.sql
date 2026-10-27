/*---
id: qb.system.audience-count
description: How many takers a publication's regions and matching conditions reach now (Q39), exactly; run by QB_AUDIENCE_COUNT only, which rounds it
entities: [QbProfile, QbCountry, QbUserBan, QbProfileAttribute, QbPublicationCriterion]
datasets:
  QbProfile: urn:jabiz:dataset:default:QbProfile
  QbCountry: urn:jabiz:dataset:default:QbCountry
  QbUserBan: urn:jabiz:dataset:default:QbUserBan
  QbProfileAttribute: urn:jabiz:dataset:default:QbProfileAttribute
  QbPublicationCriterion: urn:jabiz:dataset:default:QbPublicationCriterion
params:
  publicationId: { like: QbPublicationCriterion.publicationId, required: true, description: The publication whose conditions count }
  regions:       { kind: { type: text, maxLength: 64 }, description: "The publication's regions (comma separated); none is GLOBAL" }
  publisherId:   { kind: { type: text, maxLength: 128 }, description: "The publisher, never counted (their own publication is not theirs to answer)" }
  at:            { kind: { type: temporal, role: EVENT_TIME }, required: true, description: "The time counted for (an age is the years since the birth year in UTC)" }
results:
  takers: { kind: { type: numeric, precision: 9, scale: 0 } }
permissions: [qb.audience.system]
---*/
-- The takers with a complete profile (a country) in a region aimed at, not banned, whose attributes meet every
-- criterion the publication has a condition on: within one criterion any option or range is enough, a criterion
-- the taker did not answer is not met (audience.AudienceMatch, the one definition; qb.app.feed, qb.app.publication
-- and AttemptProcesses apply the same; AudienceSqlIT compares them). Never given to a sponsor as is.
SELECT count(*) AS takers
FROM {{QbProfile}} p
JOIN {{QbCountry}} co ON co.{{QbCountry.code}} = p.{{QbProfile.countryCode}}
WHERE string_to_array(coalesce(CAST(:regions AS text), 'GLOBAL'), ',') && string_to_array(co.{{QbCountry.regions}}, ',')
  AND (CAST(:publisherId AS text) IS NULL OR p.{{QbProfile.userId}} <> CAST(:publisherId AS text))
  AND NOT EXISTS (SELECT 1 FROM {{QbUserBan}} b
                  WHERE CAST(b.{{QbUserBan.userId}} AS text) = p.{{QbProfile.userId}} AND b.{{QbUserBan.banned}})
  AND NOT EXISTS (
      SELECT 1 FROM {{QbPublicationCriterion}} pc
      WHERE pc.{{QbPublicationCriterion.publicationId}} = CAST(:publicationId AS uuid)
      GROUP BY pc.{{QbPublicationCriterion.criterionKey}}
      HAVING NOT bool_or(EXISTS (
          SELECT 1 FROM {{QbProfileAttribute}} pa
          WHERE pa.{{QbProfileAttribute.userId}} = p.{{QbProfile.userId}}
            AND pa.{{QbProfileAttribute.criterionKey}} = pc.{{QbPublicationCriterion.criterionKey}}
            AND (pa.{{QbProfileAttribute.valueCode}} = pc.{{QbPublicationCriterion.valueCode}}
                 OR (pc.{{QbPublicationCriterion.minValue}} IS NOT NULL
                     AND CASE pc.{{QbPublicationCriterion.measure}}
                             WHEN 'YEARS_SINCE' THEN EXTRACT(YEAR FROM CAST(:at AS timestamptz) AT TIME ZONE 'UTC')
                                 - pa.{{QbProfileAttribute.valueNumber}}
                             ELSE pa.{{QbProfileAttribute.valueNumber}} END
                         BETWEEN pc.{{QbPublicationCriterion.minValue}} AND pc.{{QbPublicationCriterion.maxValue}})))))
