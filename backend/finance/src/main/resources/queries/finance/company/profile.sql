/*---
id: finance.company.profile
description: >-
  The company as its documents show it (FIN-AR-005): legal name, address lines, contact and remittance instructions,
  from the company's profile (FIN_COMPANY_PROFILE_SET).
entities: [FinCompanyProfile]
results:
  legalName:  { from: FinCompanyProfile.legalName }
  street:     { from: FinCompanyProfile.street }
  cityLine:   { kind: { type: text, maxLength: 200 } }
  country:    { from: FinCompanyProfile.country }
  phone:      { from: FinCompanyProfile.phone }
  email:      { from: FinCompanyProfile.email }
  remittance: { from: FinCompanyProfile.remittance }
list:
  sorts: [legalName]
  defaultSort: { field: legalName, asc: true }
  key: [legalName]
permissions: [fin.master.read]
---*/
SELECT
    p.{{FinCompanyProfile.legalName}}  AS legalName,
    p.{{FinCompanyProfile.street}}     AS street,
    -- "Austin, TX 78701": the parts there are.
    NULLIF(concat_ws(' ', p.{{FinCompanyProfile.city}} || ',', p.{{FinCompanyProfile.state}},
        p.{{FinCompanyProfile.postalCode}}), '') AS cityLine,
    p.{{FinCompanyProfile.country}}    AS country,
    p.{{FinCompanyProfile.phone}}      AS phone,
    p.{{FinCompanyProfile.email}}      AS email,
    p.{{FinCompanyProfile.remittance}} AS remittance
FROM {{FinCompanyProfile}} p
WHERE p.{{FinCompanyProfile.profileKey}} = 'COMPANY'
