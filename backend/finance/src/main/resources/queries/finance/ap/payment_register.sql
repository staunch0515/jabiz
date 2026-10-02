/*---
id: finance.ap.payment_register
description: >-
  Payments made on days in a range (FIN-UI-004, FIN-AP-012): number, day, the run, kind, vendor and payee, method and
  check number, amount and discount taken, and whether it was voided and when. Run with knownAt, as recorded then.
entities: [FinPayment]
params:
  from:       { like: FinPayment.paymentDate, required: true, description: first payment date }
  to:         { like: FinPayment.paymentDate, required: true, description: last payment date }
  vendorCode: { like: FinPayment.vendorCode, description: "only this vendor's, if given" }
  method:     { like: FinPayment.method, description: "only by this method, if given" }
  status:     { like: FinPayment.status, description: "only posted or only voided, if given" }
results:
  paymentId:   { from: FinPayment.paymentId }
  paymentNo:   { from: FinPayment.paymentNo }
  paymentDate: { from: FinPayment.paymentDate }
  runId:       { from: FinPayment.runId }
  runNo:       { from: FinPayment.runNo }
  kind:        { from: FinPayment.kind }
  vendorCode:  { from: FinPayment.vendorCode }
  payee:       { from: FinPayment.payee }
  method:      { from: FinPayment.method }
  checkNo:     { from: FinPayment.checkNo }
  amount:      { from: FinPayment.amount }
  discount:    { from: FinPayment.discount }
  status:      { from: FinPayment.status }
  voidDate:    { from: FinPayment.voidDate }
list:
  filters: [paymentNo, runNo, kind, vendorCode, method, checkNo, status]
  sorts:   [paymentNo, paymentDate, amount]
  defaultSort: { field: paymentDate, asc: true }
  key: [paymentId]
permissions: [fin.ap.read]
report:
  period: { from: from, to: to }
  landscape: true
---*/
SELECT
    p.{{FinPayment.paymentId}}   AS paymentId,
    p.{{FinPayment.paymentNo}}   AS paymentNo,
    p.{{FinPayment.paymentDate}} AS paymentDate,
    p.{{FinPayment.runId}}       AS runId,
    p.{{FinPayment.runNo}}       AS runNo,
    p.{{FinPayment.kind}}        AS kind,
    p.{{FinPayment.vendorCode}}  AS vendorCode,
    p.{{FinPayment.payee}}       AS payee,
    p.{{FinPayment.method}}      AS method,
    p.{{FinPayment.checkNo}}     AS checkNo,
    p.{{FinPayment.amount}}      AS amount,
    p.{{FinPayment.discount}}    AS discount,
    p.{{FinPayment.status}}      AS status,
    p.{{FinPayment.voidDate}}    AS voidDate
FROM {{FinPayment}} p
WHERE p.{{FinPayment.paymentDate}} >= :from AND p.{{FinPayment.paymentDate}} <= :to
  AND (CAST(:vendorCode AS varchar) IS NULL OR p.{{FinPayment.vendorCode}} = :vendorCode)
  AND (CAST(:method AS varchar) IS NULL OR p.{{FinPayment.method}} = :method)
  AND (CAST(:status AS varchar) IS NULL OR p.{{FinPayment.status}} = :status)
