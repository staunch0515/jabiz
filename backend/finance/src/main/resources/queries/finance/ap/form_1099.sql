/*---
id: finance.ap.form_1099
description: >-
  The Form 1099 summary of a tax year (FIN-AP-021, FIN-EXP-14): what payments made in the calendar year count on each
  vendor's form and box (cash basis; voids in their year take back), the threshold of the form for the year from the
  threshold table, and whether the vendor's form reaches it and is reported. Card payments are not counted.
entities: [Fin1099Amount, FinVendor, Fin1099Threshold]
params:
  taxYear: { like: Fin1099Amount.taxYear, required: true, description: The calendar year paid in }
results:
  vendorCode: { from: Fin1099Amount.vendorCode }
  legalName:  { from: FinVendor.legalName }
  form1099:   { from: Fin1099Amount.form1099 }
  box1099:    { from: Fin1099Amount.box1099 }
  amount:     { from: Fin1099Amount.amount }
  formTotal:  { from: Fin1099Amount.amount }
  threshold:  { from: Fin1099Threshold.threshold }
  reportable: { kind: { type: bool } }
list:
  filters: [vendorCode, form1099, reportable]
  sorts:   [vendorCode, form1099, box1099, amount]
  defaultSort: { field: vendorCode, asc: true }
  key: [vendorCode, form1099, box1099]
permissions: [fin.ap.read]
report:
  landscape: false
---*/
WITH boxes AS (
    SELECT a.{{Fin1099Amount.vendorCode}} AS vend, a.{{Fin1099Amount.form1099}} AS form,
           a.{{Fin1099Amount.box1099}} AS box, sum(a.{{Fin1099Amount.amount}}) AS amount
    FROM {{Fin1099Amount}} a
    WHERE a.{{Fin1099Amount.taxYear}} = :taxYear
    GROUP BY a.{{Fin1099Amount.vendorCode}}, a.{{Fin1099Amount.form1099}}, a.{{Fin1099Amount.box1099}}
    HAVING sum(a.{{Fin1099Amount.amount}}) <> 0
), forms AS (
    SELECT b.*, sum(b.amount) OVER (PARTITION BY b.vend, b.form) AS form_total FROM boxes b
)
SELECT
    f.vend   AS vendorCode,
    v.{{FinVendor.legalName}} AS legalName,
    f.form   AS form1099,
    f.box    AS box1099,
    f.amount AS amount,
    f.form_total AS formTotal,
    t.{{Fin1099Threshold.threshold}} AS threshold,
    -- A box is reported when it has something and the vendor's form reaches the year's threshold; no threshold for
    -- the year is no reason to leave a vendor out. A box below nothing (a void of an earlier payment) never is.
    (f.amount > 0 AND (t.{{Fin1099Threshold.threshold}} IS NULL
        OR f.form_total >= t.{{Fin1099Threshold.threshold}})) AS reportable
FROM forms f
LEFT JOIN {{FinVendor}} v ON v.{{FinVendor.vendorCode}} = f.vend
LEFT JOIN {{Fin1099Threshold}} t
  ON t.{{Fin1099Threshold.taxYear}} = :taxYear AND t.{{Fin1099Threshold.form1099}} = f.form
