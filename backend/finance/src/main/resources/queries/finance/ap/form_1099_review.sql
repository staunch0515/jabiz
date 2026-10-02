/*---
id: finance.ap.form_1099_review
description: >-
  What stands in the way of a tax year's Forms 1099 (FIN-AP-022 acceptance 1): each vendor reported on Form 1099 or
  paid in the year in a 1099 box, without a TIN, with a TIN the IRS did not match, or without a remittance address
  to mail the recipient copy to. Nothing listed: the forms can be filed.
entities: [FinVendor, FinVendorTaxInfo, Fin1099Amount]
params:
  taxYear: { like: Fin1099Amount.taxYear, required: true, description: The tax year }
results:
  vendorCode: { from: FinVendor.vendorCode }
  legalName:  { from: FinVendor.legalName }
  form1099:   { from: FinVendor.form1099 }
  amount:     { from: Fin1099Amount.amount }
  issue:      { kind: { type: text, maxLength: 20 } }
list:
  filters: [vendorCode, issue]
  sorts:   [vendorCode, issue]
  defaultSort: { field: vendorCode, asc: true }
  key: [vendorCode, issue]
permissions: [fin.1099.maintain]
report:
  landscape: false
---*/
WITH paid AS (
    SELECT a.{{Fin1099Amount.vendorCode}} AS vend, sum(a.{{Fin1099Amount.amount}}) AS amount
    FROM {{Fin1099Amount}} a
    WHERE a.{{Fin1099Amount.taxYear}} = :taxYear
    GROUP BY a.{{Fin1099Amount.vendorCode}}
), vendors AS (
    SELECT v.{{FinVendor.vendorCode}} AS vend, v.{{FinVendor.legalName}} AS name, v.{{FinVendor.form1099}} AS form,
           COALESCE(p.amount, 0) AS amount,
           v.{{FinVendor.remitStreet}} AS street, v.{{FinVendor.remitCity}} AS city,
           v.{{FinVendor.remitPostalCode}} AS zip,
           x.{{FinVendorTaxInfo.tin}} AS tin_value, x.{{FinVendorTaxInfo.tinStatus}} AS tin_check
    FROM {{FinVendor}} v
    LEFT JOIN paid p ON p.vend = v.{{FinVendor.vendorCode}}
    LEFT JOIN {{FinVendorTaxInfo}} x ON x.{{FinVendorTaxInfo.vendorCode}} = v.{{FinVendor.vendorCode}}
    WHERE (v.{{FinVendor.form1099}} IS NOT NULL AND v.{{FinVendor.status}} = 'ACTIVE') OR p.amount <> 0
)
SELECT vend AS vendorCode, name AS legalName, form AS form1099, amount, issue
FROM (
    SELECT vend, name, form, amount, 'NO_TIN' AS issue FROM vendors WHERE tin_value IS NULL
    UNION ALL
    SELECT vend, name, form, amount, 'TIN_MISMATCH' FROM vendors WHERE tin_check = 'MISMATCH'
    UNION ALL
    SELECT vend, name, form, amount, 'NO_ADDRESS' FROM vendors
     WHERE NULLIF(street, '') IS NULL OR NULLIF(city, '') IS NULL OR NULLIF(zip, '') IS NULL
) issues
