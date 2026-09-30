/*---
id: it.carrier_accounts
description: Carriers with their bank accounts, a masked field (docs/design/10-security.md section 13.1)
entities: [Carrier]
params:
  code: { like: Carrier.carrierCode, description: one carrier }
results:
  carrierCode: { from: Carrier.carrierCode }
  bankAccount: { from: Carrier.bankAccount }
list:
  filters: [carrierCode, bankAccount]
  sorts: [carrierCode]
  defaultSort: { field: carrierCode }
permissions: [logistics.carrier.read]
report: {}
---*/
SELECT c.{{Carrier.carrierCode}} AS carrierCode, c.{{Carrier.bankAccount}} AS bankAccount
FROM {{Carrier}} c
WHERE c.{{Carrier.carrierCode}} = :code
