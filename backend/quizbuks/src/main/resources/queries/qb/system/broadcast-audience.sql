/*---
id: qb.system.broadcast-audience
description: How many people an announcement or notice matches when it is sent (its audienceCount, the base of its read rate; Q7-14)
entities: [QbBroadcastTarget, QbProfile, QbCountry, QbInboxState, QbSponsor]
datasets:
  QbBroadcastTarget: urn:jabiz:dataset:default:QbBroadcastTarget
  QbProfile: urn:jabiz:dataset:default:QbProfile
  QbCountry: urn:jabiz:dataset:default:QbCountry
  QbInboxState: urn:jabiz:dataset:default:QbInboxState
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  run:         { kind: { type: bool }, required: true, description: "false: 0 without counting (the process does not send now)" }
  audience:    { kind: { type: text, maxLength: 10 }, required: true, description: APP or SPONSOR }
  type:        { kind: { type: text, maxLength: 10 }, required: true, description: "SYSTEM, NOTICE or PROMO" }
  targetMode:  { kind: { type: text, maxLength: 10 }, required: true, description: "ALL, REGIONS or USERS" }
  regions:     { kind: { type: text, maxLength: 64 }, description: The regions aimed at (REGIONS) }
  broadcastId: { like: QbBroadcastTarget.broadcastId, description: The announcement (its named users) }
  senderId:    { kind: { type: text, maxLength: 128 }, description: "A sponsor's notice: the sponsor, never counted" }
  at:          { kind: { type: temporal, role: EVENT_TIME }, required: true, description: When it is sent }
results:
  audienceCount: { kind: { type: numeric, precision: 9, scale: 0 } }
permissions: [qb.messaging.system]
---*/
-- The matching of qb.app.inbox and qb.sponsor.inbox (InboxMatchIT) as of the sending, counted once: named users
-- are their list; the app's audience is the takers with a complete profile (a country), in a region aimed at; the
-- sponsor console's the approved sponsors. A promotion counts only those registered (approved) by then who did not
-- turn promotions off. Run once per sending, over the current profiles; never per reader.
SELECT CASE
    WHEN NOT CAST(:run AS boolean) THEN 0
    WHEN CAST(:targetMode AS text) = 'USERS' THEN
        (SELECT count(*) FROM {{QbBroadcastTarget}} t
         WHERE t.{{QbBroadcastTarget.broadcastId}} = CAST(:broadcastId AS uuid))
    WHEN CAST(:audience AS text) = 'SPONSOR' THEN
        (SELECT count(*) FROM {{QbSponsor}} s
         LEFT JOIN {{QbInboxState}} st ON st.{{QbInboxState.userId}} = s.{{QbSponsor.userId}}
         WHERE s.{{QbSponsor.reviewStatus}} = 'APPROVED'
           AND (CAST(:type AS text) <> 'PROMO'
                OR s.{{QbSponsor.approvedAt}} <= :at AND NOT coalesce(st.{{QbInboxState.promoOff}}, false)))
    ELSE
        (SELECT count(*) FROM {{QbProfile}} p
         JOIN {{QbCountry}} co ON co.{{QbCountry.code}} = p.{{QbProfile.countryCode}}
         LEFT JOIN {{QbInboxState}} st ON st.{{QbInboxState.userId}} = p.{{QbProfile.userId}}
         WHERE (CAST(:targetMode AS text) = 'ALL'
                OR string_to_array(CAST(:regions AS text), ',') && string_to_array(co.{{QbCountry.regions}}, ','))
           AND (CAST(:senderId AS text) IS NULL OR p.{{QbProfile.userId}} <> CAST(:senderId AS text))
           AND (CAST(:type AS text) <> 'PROMO'
                OR p.{{QbProfile.createdAt}} <= :at AND NOT coalesce(st.{{QbInboxState.promoOff}}, false)))
    END AS audienceCount
