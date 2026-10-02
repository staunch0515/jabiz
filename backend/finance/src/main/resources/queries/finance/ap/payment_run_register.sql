/*---
id: finance.ap.payment_run_register
description: >-
  Payment runs to pay on days in a range (FIN-UI-004, FIN-AP-010, FIN-AP-011): number, day, method, bank account,
  state, total and lines, who prepared, approved and released each. Run with knownAt, the runs as recorded then.
entities: [FinPaymentRun]
params:
  from:   { like: FinPaymentRun.paymentDate, required: true, description: first payment date }
  to:     { like: FinPaymentRun.paymentDate, required: true, description: last payment date }
  status: { like: FinPaymentRun.status, description: "only runs in this state, if given" }
results:
  runId:       { from: FinPaymentRun.runId }
  runNo:       { from: FinPaymentRun.runNo }
  paymentDate: { from: FinPaymentRun.paymentDate }
  method:      { from: FinPaymentRun.method }
  bankCode:    { from: FinPaymentRun.bankCode }
  status:      { from: FinPaymentRun.status }
  total:       { from: FinPaymentRun.total }
  lineCount:   { from: FinPaymentRun.lineCount }
  description: { from: FinPaymentRun.description }
  preparedBy:  { from: FinPaymentRun.preparedBy }
  approvedBy:  { from: FinPaymentRun.approvedBy }
  releasedBy:  { from: FinPaymentRun.releasedBy }
list:
  filters: [runNo, method, status, bankCode]
  sorts:   [runNo, paymentDate, total]
  defaultSort: { field: paymentDate, asc: true }
  key: [runId]
permissions: [fin.ap.read]
report:
  period: { from: from, to: to }
  landscape: true
---*/
SELECT
    r.{{FinPaymentRun.runId}}       AS runId,
    r.{{FinPaymentRun.runNo}}       AS runNo,
    r.{{FinPaymentRun.paymentDate}} AS paymentDate,
    r.{{FinPaymentRun.method}}      AS method,
    r.{{FinPaymentRun.bankCode}}    AS bankCode,
    r.{{FinPaymentRun.status}}      AS status,
    r.{{FinPaymentRun.total}}       AS total,
    r.{{FinPaymentRun.lineCount}}   AS lineCount,
    r.{{FinPaymentRun.description}} AS description,
    r.{{FinPaymentRun.preparedBy}}  AS preparedBy,
    r.{{FinPaymentRun.approvedBy}}  AS approvedBy,
    r.{{FinPaymentRun.releasedBy}}  AS releasedBy
FROM {{FinPaymentRun}} r
WHERE r.{{FinPaymentRun.paymentDate}} >= :from AND r.{{FinPaymentRun.paymentDate}} <= :to
  AND (CAST(:status AS varchar) IS NULL OR r.{{FinPaymentRun.status}} = :status)
