/*---
id: qb.sponsor.mail-server
description: The sponsor's own mail server (Q29) as the console shows it, never its password; verified, or the server's last answer
entities: [QbSponsorMailServer]
datasets:
  QbSponsorMailServer: urn:jabiz:dataset:sponsor:QbSponsorMailServer
results:
  sponsorId:   { from: QbSponsorMailServer.sponsorId }
  host:        { from: QbSponsorMailServer.host }
  port:        { from: QbSponsorMailServer.port }
  tls:         { from: QbSponsorMailServer.tls }
  username:    { from: QbSponsorMailServer.username }
  fromAddress: { from: QbSponsorMailServer.fromAddress }
  fromName:    { from: QbSponsorMailServer.fromName }
  verifiedAt:  { from: QbSponsorMailServer.verifiedAt }
  testedAt:    { from: QbSponsorMailServer.testedAt }
  lastError:   { from: QbSponsorMailServer.lastError }
list:
  key: [sponsorId]
permissions: [qb.publication.write]
---*/
-- The sponsor's own row only (the dataset's scope): one or none.
SELECT
    s.{{QbSponsorMailServer.sponsorId}}   AS sponsorId,
    s.{{QbSponsorMailServer.host}}        AS host,
    s.{{QbSponsorMailServer.port}}        AS port,
    s.{{QbSponsorMailServer.tls}}         AS tls,
    s.{{QbSponsorMailServer.username}}    AS username,
    s.{{QbSponsorMailServer.fromAddress}} AS fromAddress,
    s.{{QbSponsorMailServer.fromName}}    AS fromName,
    s.{{QbSponsorMailServer.verifiedAt}}  AS verifiedAt,
    s.{{QbSponsorMailServer.testedAt}}    AS testedAt,
    s.{{QbSponsorMailServer.lastError}}   AS lastError
FROM {{QbSponsorMailServer}} s
