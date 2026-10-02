/*---
id: finance.ap.form_1099_copy_lines
description: >-
  The amounts of a vendor's recipient copy (FIN-AP-022): each form and box reported, as finance.ap.form_1099 decides
  it (something in the box, the vendor's form at or above the year's threshold), with what the payments of the tax
  year count in it.
entities: [Fin1099Amount, FinVendor, Fin1099Threshold]
params:
  vendorId: { like: FinVendor.vendorId, required: true, description: The vendor }
  taxYear:  { like: Fin1099Amount.taxYear, required: true, description: The tax year }
results:
  form1099: { from: Fin1099Amount.form1099 }
  box1099:  { from: Fin1099Amount.box1099 }
  boxName:  { kind: { type: text, maxLength: 60 } }
  amount:   { from: Fin1099Amount.amount }
list:
  sorts: [form1099, box1099]
  defaultSort: { field: form1099, asc: true }
  key: [form1099, box1099]
permissions: [fin.1099.file]
---*/
WITH boxes AS (
    SELECT a.{{Fin1099Amount.form1099}} AS form, a.{{Fin1099Amount.box1099}} AS box,
           sum(a.{{Fin1099Amount.amount}}) AS amount
    FROM {{Fin1099Amount}} a
    JOIN {{FinVendor}} v ON v.{{FinVendor.vendorCode}} = a.{{Fin1099Amount.vendorCode}}
    WHERE v.{{FinVendor.vendorId}} = :vendorId AND a.{{Fin1099Amount.taxYear}} = :taxYear
    GROUP BY a.{{Fin1099Amount.form1099}}, a.{{Fin1099Amount.box1099}}
    HAVING sum(a.{{Fin1099Amount.amount}}) <> 0
), forms AS (
    SELECT b.*, sum(b.amount) OVER (PARTITION BY b.form) AS form_total FROM boxes b
)
SELECT
    f.form AS form1099,
    f.box  AS box1099,
    CASE f.form || '-' || f.box
        WHEN 'NEC-1'   THEN 'Nonemployee compensation'
        WHEN 'MISC-1'  THEN 'Rents'
        WHEN 'MISC-2'  THEN 'Royalties'
        WHEN 'MISC-3'  THEN 'Other income'
        WHEN 'MISC-6'  THEN 'Medical and health care payments'
        WHEN 'MISC-10' THEN 'Gross proceeds paid to an attorney'
    END AS boxName,
    f.amount AS amount
FROM forms f
LEFT JOIN {{Fin1099Threshold}} t
  ON t.{{Fin1099Threshold.taxYear}} = :taxYear AND t.{{Fin1099Threshold.form1099}} = f.form
WHERE f.amount > 0
  AND (t.{{Fin1099Threshold.threshold}} IS NULL OR f.form_total >= t.{{Fin1099Threshold.threshold}})
