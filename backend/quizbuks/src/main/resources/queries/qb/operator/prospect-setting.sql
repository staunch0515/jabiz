/*---
id: qb.operator.prospect-setting
description: "The caller operator's prospecting settings (Q48c), with its mail server's state (never its password); none before the first save"
entities: [QbProspectSetting, QbSponsorMailServer, QbOperatorMember]
datasets:
  QbProspectSetting: urn:jabiz:dataset:default:QbProspectSetting
  QbSponsorMailServer: urn:jabiz:dataset:default:QbSponsorMailServer
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
results:
  owner:          { from: QbProspectSetting.owner }
  enabled:        { from: QbProspectSetting.enabled }
  dailyCap:       { from: QbProspectSetting.dailyCap }
  minScore:       { from: QbProspectSetting.minScore }
  monthlyCostCap: { from: QbProspectSetting.monthlyCostCap }
  senderName:     { from: QbProspectSetting.senderName }
  senderAddress:  { from: QbProspectSetting.senderAddress }
  senderContact:  { from: QbProspectSetting.senderContact }
  autoApproveScore: { from: QbProspectSetting.autoApproveScore }
  updatedAt:      { from: QbProspectSetting.updatedAt }
  revision:       { from: QbProspectSetting.revision }
  serverHost:     { from: QbSponsorMailServer.host }
  serverPort:     { from: QbSponsorMailServer.port }
  serverTls:      { from: QbSponsorMailServer.tls }
  serverUsername: { from: QbSponsorMailServer.username }
  fromAddress:    { from: QbSponsorMailServer.fromAddress }
  fromName:       { from: QbSponsorMailServer.fromName }
  verifiedAt:     { from: QbSponsorMailServer.verifiedAt }
  lastError:      { from: QbSponsorMailServer.lastError }
list:
  key: [owner]
permissions: [qb.prospect.use]
---*/
-- One row for the owner, with or without settings or a server (the sender key of its server is prospect:<owner>).
SELECT
    CAST(me.{{QbOperatorMember.operatorId}} AS text) AS owner,
    s.{{QbProspectSetting.enabled}} AS enabled, s.{{QbProspectSetting.dailyCap}} AS dailyCap,
    s.{{QbProspectSetting.minScore}} AS minScore, s.{{QbProspectSetting.monthlyCostCap}} AS monthlyCostCap,
    s.{{QbProspectSetting.senderName}} AS senderName, s.{{QbProspectSetting.senderAddress}} AS senderAddress,
    s.{{QbProspectSetting.senderContact}} AS senderContact, s.{{QbProspectSetting.updatedAt}} AS updatedAt,
    s.{{QbProspectSetting.autoApproveScore}} AS autoApproveScore,
    s.{{QbProspectSetting.revision}} AS revision,
    m.{{QbSponsorMailServer.host}} AS serverHost, m.{{QbSponsorMailServer.port}} AS serverPort,
    m.{{QbSponsorMailServer.tls}} AS serverTls, m.{{QbSponsorMailServer.username}} AS serverUsername,
    m.{{QbSponsorMailServer.fromAddress}} AS fromAddress, m.{{QbSponsorMailServer.fromName}} AS fromName,
    m.{{QbSponsorMailServer.verifiedAt}} AS verifiedAt, m.{{QbSponsorMailServer.lastError}} AS lastError
FROM {{QbOperatorMember}} me
LEFT JOIN {{QbProspectSetting}} s ON s.{{QbProspectSetting.owner}} = CAST(me.{{QbOperatorMember.operatorId}} AS text)
LEFT JOIN {{QbSponsorMailServer}} m ON m.{{QbSponsorMailServer.sponsorId}} = 'prospect:' || CAST(me.{{QbOperatorMember.operatorId}} AS text)
WHERE me.{{QbOperatorMember.removedAt}} IS NULL
