/*---
id: qb.admin.prospect-blocks
description: The addresses and domains prospecting never writes to (Q48c), whoever recorded them
entities: [QbProspectBlock]
datasets:
  QbProspectBlock: urn:jabiz:dataset:default:QbProspectBlock
params:
  q: { kind: { type: text, maxLength: 100 }, description: Only blocks whose address or domain contains this text }
results:
  blockId:   { from: QbProspectBlock.blockId }
  kind:      { from: QbProspectBlock.kind }
  value:     { from: QbProspectBlock.value }
  reason:    { from: QbProspectBlock.reason }
  owner:     { from: QbProspectBlock.owner }
  note:      { from: QbProspectBlock.note }
  createdAt: { from: QbProspectBlock.createdAt }
  createdBy: { from: QbProspectBlock.createdBy }
list:
  filters: [kind, reason, createdAt]
  sorts:   [createdAt]
  defaultSort: { field: createdAt, asc: false }
  key: [blockId]
permissions: [qb.admin.prospect]
---*/
SELECT b.{{QbProspectBlock.blockId}} AS blockId, b.{{QbProspectBlock.kind}} AS kind,
       b.{{QbProspectBlock.value}} AS value, b.{{QbProspectBlock.reason}} AS reason,
       b.{{QbProspectBlock.owner}} AS owner, b.{{QbProspectBlock.note}} AS note,
       b.{{QbProspectBlock.createdAt}} AS createdAt, b.{{QbProspectBlock.createdBy}} AS createdBy
FROM {{QbProspectBlock}} b
WHERE CAST(:q AS text) IS NULL OR strpos(b.{{QbProspectBlock.value}}, lower(:q)) > 0
