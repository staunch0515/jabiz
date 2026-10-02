/*---
id: finance.ap.form_1099_copy_header
description: >-
  The recipient copy of a vendor's Forms 1099 for a tax year (FIN-AP-022): the payer with its EIN, the recipient with
  the address on file and the TIN truncated as the IRS allows on recipient copies, the vendor's code as the account
  number.
entities: [FinVendor, FinVendorTaxInfo, FinCompanyProfile, Fin1099Amount]
params:
  vendorId: { like: FinVendor.vendorId, required: true, description: The vendor }
  taxYear:  { like: Fin1099Amount.taxYear, required: true, description: The tax year }
results:
  documentNo:    { kind: { type: text, maxLength: 40 } }
  taxYear:       { from: Fin1099Amount.taxYear }
  payerName:     { from: FinCompanyProfile.legalName }
  payerStreet:   { from: FinCompanyProfile.street }
  payerCityLine: { kind: { type: text, maxLength: 200 } }
  payerTin:      { from: FinCompanyProfile.taxId }
  recipientName: { from: FinVendor.legalName }
  recipientStreet:   { from: FinVendor.remitStreet }
  recipientCityLine: { kind: { type: text, maxLength: 200 } }
  recipientTin:  { from: FinVendorTaxInfo.tin }
  accountNumber: { from: FinVendor.vendorCode }
list:
  sorts: [documentNo]
  defaultSort: { field: documentNo, asc: true }
  key: [documentNo]
permissions: [fin.1099.file]
---*/
SELECT
    '1099-' || CAST(y.yr AS varchar) || '-' || v.{{FinVendor.vendorCode}} AS documentNo,
    y.yr AS taxYear,
    p.{{FinCompanyProfile.legalName}} AS payerName,
    p.{{FinCompanyProfile.street}} AS payerStreet,
    NULLIF(concat_ws(', ', NULLIF(p.{{FinCompanyProfile.city}}, ''),
        NULLIF(concat_ws(' ', NULLIF(p.{{FinCompanyProfile.state}}, ''),
            NULLIF(p.{{FinCompanyProfile.postalCode}}, '')), '')), '') AS payerCityLine,
    p.{{FinCompanyProfile.taxId}} AS payerTin,
    v.{{FinVendor.legalName}} AS recipientName,
    v.{{FinVendor.remitStreet}} AS recipientStreet,
    NULLIF(concat_ws(', ', NULLIF(v.{{FinVendor.remitCity}}, ''),
        NULLIF(concat_ws(' ', NULLIF(v.{{FinVendor.remitState}}, ''),
            NULLIF(v.{{FinVendor.remitPostalCode}}, '')), '')), '') AS recipientCityLine,
    x.{{FinVendorTaxInfo.tin}} AS recipientTin,
    v.{{FinVendor.vendorCode}} AS accountNumber
FROM {{FinVendor}} v
LEFT JOIN {{FinVendorTaxInfo}} x ON x.{{FinVendorTaxInfo.vendorCode}} = v.{{FinVendor.vendorCode}}
CROSS JOIN {{FinCompanyProfile}} p
CROSS JOIN (SELECT CAST(:taxYear AS numeric(4,0)) AS yr) y
WHERE v.{{FinVendor.vendorId}} = :vendorId
