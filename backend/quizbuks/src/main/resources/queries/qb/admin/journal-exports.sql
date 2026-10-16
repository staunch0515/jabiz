/*---
id: qb.admin.journal-exports
description: >-
  The journal exports (Q9d, F-02 / F-03), newest period first, with the voucher's file, how many files the detail
  takes (qb.admin.journal-export-parts lists them) and the accounting system's voucher number once recorded
entities: [QbJournalExport, QbJournalExportConfirmation]
datasets:
  QbJournalExport: urn:jabiz:dataset:default:QbJournalExport
  QbJournalExportConfirmation: urn:jabiz:dataset:default:QbJournalExportConfirmation
results:
  exportId:          { from: QbJournalExport.exportId }
  periodFrom:        { from: QbJournalExport.periodFrom }
  periodTo:          { from: QbJournalExport.periodTo }
  voucherNo:         { from: QbJournalExport.voucherNo }
  lineCount:         { from: QbJournalExport.lineCount }
  transactionCount:  { from: QbJournalExport.transactionCount }
  debitTotal:        { from: QbJournalExport.debitTotal }
  creditTotal:       { from: QbJournalExport.creditTotal }
  knownAt:           { from: QbJournalExport.knownAt }
  exportedAt:        { from: QbJournalExport.createdTime }
  exportedBy:        { from: QbJournalExport.exportedBy }
  fileId:            { from: QbJournalExport.fileId }
  fileSha256:        { from: QbJournalExport.fileSha256 }
  detailPartCount:   { from: QbJournalExport.detailPartCount }
  externalVoucherNo: { from: QbJournalExportConfirmation.externalVoucherNo }
  confirmedBy:       { from: QbJournalExportConfirmation.confirmedBy }
list:
  filters: [periodFrom, voucherNo, externalVoucherNo]
  sorts:   [periodFrom]
  defaultSort: { field: periodFrom, asc: false }
  key: [exportId]
permissions: [qb.admin.finance.read]
---*/
SELECT
    x.{{QbJournalExport.exportId}}         AS exportId,
    x.{{QbJournalExport.periodFrom}}       AS periodFrom,
    x.{{QbJournalExport.periodTo}}         AS periodTo,
    x.{{QbJournalExport.voucherNo}}        AS voucherNo,
    x.{{QbJournalExport.lineCount}}        AS lineCount,
    x.{{QbJournalExport.transactionCount}} AS transactionCount,
    x.{{QbJournalExport.debitTotal}}       AS debitTotal,
    x.{{QbJournalExport.creditTotal}}      AS creditTotal,
    x.{{QbJournalExport.knownAt}}          AS knownAt,
    x.{{QbJournalExport.createdTime}}      AS exportedAt,
    x.{{QbJournalExport.exportedBy}}       AS exportedBy,
    x.{{QbJournalExport.fileId}}           AS fileId,
    x.{{QbJournalExport.fileSha256}}       AS fileSha256,
    x.{{QbJournalExport.detailPartCount}}  AS detailPartCount,
    c.{{QbJournalExportConfirmation.externalVoucherNo}} AS externalVoucherNo,
    c.{{QbJournalExportConfirmation.confirmedBy}}       AS confirmedBy
FROM {{QbJournalExport}} x
LEFT JOIN {{QbJournalExportConfirmation}} c
    ON c.{{QbJournalExportConfirmation.exportId}} = x.{{QbJournalExport.exportId}}
