/*---
id: qb.app.prizes
description: "F-F4: the taker's own prizes of all three kinds - to collect at a sponsor's place, owed in cash, digital - newest first, with whether each is still open"
entities: [QbRedemption, QbCashReward, QbDigitalPrize, QbPublicationCard, QbSponsor]
datasets:
  QbRedemption: urn:jabiz:dataset:taker:QbRedemption
  QbCashReward: urn:jabiz:dataset:taker:QbCashReward
  QbDigitalPrize: urn:jabiz:dataset:taker:QbDigitalPrize
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  open: { kind: { type: bool }, description: "Only prizes that still need something (to collect, to confirm, to settle); without it every prize" }
results:
  kind:            { kind: { type: text, maxLength: 10 } }
  prizeId:         { from: QbRedemption.redemptionId }
  attemptId:       { from: QbRedemption.attemptId }
  sourceKind:      { from: QbRedemption.sourceKind }
  code:            { from: QbRedemption.code }
  publicationId:   { from: QbRedemption.publicationId }
  publicationName: { from: QbPublicationCard.name }
  sponsorCompany:  { from: QbSponsor.company }
  amount:          { from: QbRedemption.amount }
  description:     { from: QbRedemption.description }
  status:          { kind: { type: text, maxLength: 20 } }
  dueAt:           { from: QbRedemption.dueAt }
  expiresAt:       { from: QbRedemption.expiresAt }
  confirmBy:       { from: QbRedemption.confirmBy }
list:
  filters: [kind, status, dueAt]
  sorts:   [dueAt, amount, status]
  defaultSort: { field: dueAt, asc: false }
  key: [kind, prizeId]
permissions: [qb.me]
---*/
-- The App's "to collect" (F-F4): one list for the "Answered" area's tab, its two badges, the numbers of "Me" and the
-- pass shown at the counter, so that they all say the same. A prize is open by the same conditions as the lists it
-- joins: to collect (qb.app.redemptions: due or claimed), cash (qb.app.cash-rewards: due, marked paid, overdue or
-- disputed), digital (due, delivered, overdue or disputed; a delivered one is confirmed by the task after confirmBy).
-- The prizes a referral earned (sourceKind) are in, as in those lists. Only the caller's own, by the taker datasets.
WITH prizes AS (
    SELECT 'REDEEM' AS kind, r.{{QbRedemption.redemptionId}} AS prizeId, r.{{QbRedemption.attemptId}} AS attemptId,
           r.{{QbRedemption.sourceKind}} AS sourceKind, r.{{QbRedemption.code}} AS code,
           r.{{QbRedemption.publicationId}} AS publicationId,
           r.{{QbRedemption.publisherId}} AS publisherId, r.{{QbRedemption.amount}} AS amount,
           r.{{QbRedemption.description}} AS description, CAST(r.{{QbRedemption.status}} AS text) AS status,
           r.{{QbRedemption.dueAt}} AS dueAt, r.{{QbRedemption.expiresAt}} AS expiresAt,
           r.{{QbRedemption.confirmBy}} AS confirmBy,
           r.{{QbRedemption.status}} IN ('DUE', 'CLAIMED') AS isOpen
    FROM {{QbRedemption}} r
    UNION ALL
    SELECT 'CASH', c.{{QbCashReward.cashRewardId}}, c.{{QbCashReward.attemptId}}, c.{{QbCashReward.sourceKind}},
           c.{{QbCashReward.code}}, c.{{QbCashReward.publicationId}}, c.{{QbCashReward.publisherId}}, c.{{QbCashReward.amount}},
           CAST(NULL AS text), CAST(c.{{QbCashReward.status}} AS text), c.{{QbCashReward.dueAt}},
           CAST(NULL AS timestamptz), c.{{QbCashReward.confirmBy}},
           c.{{QbCashReward.status}} IN ('DUE', 'MARKED_PAID', 'OVERDUE', 'DISPUTED')
    FROM {{QbCashReward}} c
    UNION ALL
    SELECT 'DIGITAL', d.{{QbDigitalPrize.digitalPrizeId}}, d.{{QbDigitalPrize.attemptId}}, d.{{QbDigitalPrize.sourceKind}},
           CAST(NULL AS text), d.{{QbDigitalPrize.publicationId}}, d.{{QbDigitalPrize.publisherId}}, d.{{QbDigitalPrize.amount}},
           d.{{QbDigitalPrize.description}}, CAST(d.{{QbDigitalPrize.status}} AS text), d.{{QbDigitalPrize.dueAt}},
           CAST(NULL AS timestamptz), d.{{QbDigitalPrize.confirmBy}},
           d.{{QbDigitalPrize.status}} IN ('DUE', 'DELIVERED', 'OVERDUE', 'DISPUTED')
    FROM {{QbDigitalPrize}} d)
SELECT p.kind AS kind, p.prizeId AS prizeId, p.attemptId AS attemptId, p.sourceKind AS sourceKind, p.code AS code,
       p.publicationId AS publicationId, c.{{QbPublicationCard.name}} AS publicationName,
       sp.{{QbSponsor.company}} AS sponsorCompany, p.amount AS amount, p.description AS description,
       p.status AS status, p.dueAt AS dueAt, p.expiresAt AS expiresAt, p.confirmBy AS confirmBy
FROM prizes p
JOIN {{QbPublicationCard}} c ON c.{{QbPublicationCard.publicationId}} = p.publicationId
LEFT JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = p.publisherId
WHERE (CAST(:open AS boolean) IS NULL OR CAST(:open AS boolean) = false OR p.isOpen)
