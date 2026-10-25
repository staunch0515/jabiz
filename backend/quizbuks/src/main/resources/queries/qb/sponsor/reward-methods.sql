/*---
id: qb.sponsor.reward-methods
description: >-
  The reward methods the sponsor may publish with now (Q28): their own setting, or without one the parameter
  qb.reward.default-methods; and the highest reward one attempt may win (qb.reward.max-per-attempt, none for 0)
entities: [QbSponsorRewardMethods, SysParam]
datasets:
  QbSponsorRewardMethods: urn:jabiz:dataset:sponsor:QbSponsorRewardMethods
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  methods:             { kind: { type: text, maxLength: 100 } }
  own:                 { kind: { type: bool } }
  maxRewardPerAttempt: { kind: { type: monetary, currency: JPY, scale: 0 } }
permissions: [qb.publication.write]
---*/
-- One row, for the wizard's choice and the console's navigation. The rule is the processes' (publishing.RewardMethod
-- and PublicationEditing, which decide when a draft is saved or submitted): the sponsor's own row of the setting, or
-- the parameter's names when there is none; before setup the parameter is missing and the platform's Kudos alone
-- apply, as the processes read it. The parameters are read at the server's clock (the dataset without time travel).
-- Names that are no method are left to the console to drop, as the processes drop them.
WITH params AS (
    SELECT
        max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.reward.default-methods' THEN p.{{SysParam.value}} END) AS p_methods,
        max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.reward.max-per-attempt' THEN p.{{SysParam.value}} END) AS p_max
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} IN ('qb.reward.default-methods', 'qb.reward.max-per-attempt')),
mine AS (
    SELECT max(m.{{QbSponsorRewardMethods.methods}}) AS methods, count(*) AS rows_found
    FROM {{QbSponsorRewardMethods}} m)
SELECT
    coalesce(mine.methods, params.p_methods, 'PLATFORM') AS methods,
    mine.rows_found > 0 AS own,
    CASE WHEN params.p_max ~ '^[0-9]{1,18}$' AND CAST(params.p_max AS numeric) > 0
        THEN CAST(params.p_max AS numeric) END AS maxRewardPerAttempt
FROM mine CROSS JOIN params
