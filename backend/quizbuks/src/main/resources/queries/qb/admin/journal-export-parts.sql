/*---
id: qb.admin.journal-export-parts
description: The detail files of some journal exports (Q9d, review H1), one per part, in order
entities: [QbJournalExport, QbJournalExportPart]
datasets:
  QbJournalExport: urn:jabiz:dataset:default:QbJournalExport
  QbJournalExportPart: urn:jabiz:dataset:default:QbJournalExportPart
params:
  exportIds: { like: QbJournalExport.exportId, list: true, required: true, description: "The exports (those of a page)" }
results:
  exportId:  { from: QbJournalExportPart.exportId }
  voucherNo: { from: QbJournalExport.voucherNo }
  partNo:    { from: QbJournalExportPart.partNo }
  rowCount:  { from: QbJournalExportPart.rowCount }
  fileId:    { from: QbJournalExportPart.fileId }
  sha256:    { from: QbJournalExportPart.sha256 }
list:
  sorts:   [partNo]
  defaultSort: { field: partNo, asc: true }
  key: [exportId, partNo]
permissions: [qb.admin.finance.read]
---*/
SELECT
    p.{{QbJournalExportPart.exportId}} AS exportId,
    x.{{QbJournalExport.voucherNo}}    AS voucherNo,
    p.{{QbJournalExportPart.partNo}}   AS partNo,
    p.{{QbJournalExportPart.rowCount}} AS rowCount,
    p.{{QbJournalExportPart.fileId}}   AS fileId,
    p.{{QbJournalExportPart.sha256}}   AS sha256
FROM {{QbJournalExportPart}} p
JOIN {{QbJournalExport}} x ON x.{{QbJournalExport.exportId}} = p.{{QbJournalExportPart.exportId}}
WHERE p.{{QbJournalExportPart.exportId}} = ANY(:exportIds)
