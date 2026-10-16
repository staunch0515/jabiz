/*---
id: qb.admin.users
description: Every user with their profile, onboarding and ban (P-01); searched by user name, address, name or company
entities: [SecUser, QbProfile, QbSponsor, QbUserBan, QbCountry]
datasets:
  QbProfile: urn:jabiz:dataset:default:QbProfile
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbUserBan: urn:jabiz:dataset:default:QbUserBan
  QbCountry: urn:jabiz:dataset:default:QbCountry
params:
  q:      { kind: { type: text, maxLength: 100 }, description: "Only users whose user name, address, name or company contains this text" }
  region: { kind: { type: text, maxLength: 20 }, description: Only users whose country is in this region (country.Regions) }
  userId: { like: SecUser.userId, description: "Only this user (the user summary of the admin extension, P-01)" }
results:
  userId:            { from: SecUser.userId }
  userName:          { from: SecUser.userName }
  email:             { from: SecUser.email }
  emailVerified:     { kind: { type: bool } }
  enabled:           { from: SecUser.enabled }
  displayName:       { from: QbProfile.displayName }
  countryCode:       { from: QbProfile.countryCode }
  countryName:       { from: QbCountry.name }
  city:              { from: QbProfile.city }
  preferredCurrency: { from: QbProfile.preferredCurrency }
  registeredAt:      { from: QbProfile.createdAt }
  sponsorStatus:     { from: QbSponsor.reviewStatus }
  company:           { from: QbSponsor.company }
  banned:            { kind: { type: bool } }
list:
  filters: [countryCode, registeredAt, sponsorStatus, banned, emailVerified, enabled]
  sorts:   [userName, registeredAt, displayName]
  defaultSort: { field: userName, asc: true }
  key: [userId]
permissions: [qb.admin.users.read]
---*/
-- The text is found as written: strpos takes it literally, so % _ and \ match themselves. An address is verified
-- while it is the one verified, in any case (docs/design/10-security.md section 15.4). The registration time is the
-- profile's first save (registration's second step, D-Q2-2): users without a profile have none. A region matches a
-- whole code of the country's comma-separated regions. One user is looked up by the key (a parameter, so it is a
-- condition on SecUser rather than on the joined result).
SELECT
    u.{{SecUser.userId}}                AS userId,
    u.{{SecUser.userName}}              AS userName,
    u.{{SecUser.email}}                 AS email,
    coalesce(lower(u.{{SecUser.verifiedEmail}}) = lower(u.{{SecUser.email}}), false) AS emailVerified,
    u.{{SecUser.enabled}}               AS enabled,
    p.{{QbProfile.displayName}}         AS displayName,
    p.{{QbProfile.countryCode}}         AS countryCode,
    c.{{QbCountry.name}}                AS countryName,
    p.{{QbProfile.city}}                AS city,
    p.{{QbProfile.preferredCurrency}}   AS preferredCurrency,
    p.{{QbProfile.createdAt}}           AS registeredAt,
    s.{{QbSponsor.reviewStatus}}        AS sponsorStatus,
    s.{{QbSponsor.company}}             AS company,
    coalesce(b.{{QbUserBan.banned}}, false) AS banned
FROM {{SecUser}} u
LEFT JOIN {{QbProfile}} p ON p.{{QbProfile.userId}} = CAST(u.{{SecUser.userId}} AS text)
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = CAST(u.{{SecUser.userId}} AS text)
LEFT JOIN {{QbUserBan}} b ON b.{{QbUserBan.userId}} = u.{{SecUser.userId}}
LEFT JOIN {{QbCountry}} c ON c.{{QbCountry.code}} = p.{{QbProfile.countryCode}}
WHERE (CAST(:q AS text) IS NULL
       OR strpos(lower(u.{{SecUser.userName}}), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(u.{{SecUser.email}}, '')), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(p.{{QbProfile.displayName}}, '')), lower(CAST(:q AS text))) > 0
       OR strpos(lower(coalesce(s.{{QbSponsor.company}}, '')), lower(CAST(:q AS text))) > 0)
  AND (CAST(:region AS text) IS NULL
       OR strpos(',' || coalesce(c.{{QbCountry.regions}}, '') || ',', ',' || CAST(:region AS text) || ',') > 0)
  AND (CAST(:userId AS uuid) IS NULL OR u.{{SecUser.userId}} = CAST(:userId AS uuid))
