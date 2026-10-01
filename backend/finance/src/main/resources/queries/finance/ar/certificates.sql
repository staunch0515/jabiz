/*---
id: finance.ar.certificates
description: >-
  Exemption and resale certificates to look after on a date (FIN-TX-004): customers whose tax code needs a
  certificate but who hold no valid one for the code's state (MISSING), and certificates that expire within the
  given number of days (EXPIRING) or have expired while their customer still needs them (EXPIRED).
entities: [FinCustomer, FinTaxCode, FinExemptionCertificate]
params:
  onDate: { like: FinExemptionCertificate.expiryDate, required: true, description: the date the certificates are checked on }
  days:   { kind: { type: numeric, precision: 4, scale: 0 }, description: "days ahead that count as expiring; 30 if not given" }
results:
  status:        { kind: { type: text, maxLength: 10 } }
  customerCode:  { from: FinCustomer.customerCode }
  legalName:     { from: FinCustomer.legalName }
  taxCode:       { from: FinCustomer.taxCode }
  state:         { from: FinTaxCode.state }
  certificateNo: { from: FinExemptionCertificate.certificateNo }
  expiryDate:    { from: FinExemptionCertificate.expiryDate }
list:
  filters: [status, customerCode, state]
  sorts:   [status, customerCode, expiryDate]
  defaultSort: { field: customerCode, asc: true }
  key: [status, customerCode, state, certificateNo]
permissions: [fin.ar.read]
report:
  period: { to: onDate }
---*/
WITH needed AS (
    SELECT c.{{FinCustomer.customerCode}} AS cust, c.{{FinCustomer.legalName}} AS cname,
           c.{{FinCustomer.taxCode}} AS ctax, t.{{FinTaxCode.state}} AS state
    FROM {{FinCustomer}} c
    JOIN {{FinTaxCode}} t ON t.{{FinTaxCode.taxCode}} = c.{{FinCustomer.taxCode}}
    WHERE t.{{FinTaxCode.certificateRequired}} AND c.{{FinCustomer.status}} = 'ACTIVE'
),
held AS (
    SELECT e.{{FinExemptionCertificate.customerCode}} AS cust, e.{{FinExemptionCertificate.state}} AS state,
           e.{{FinExemptionCertificate.certificateNo}} AS cert,
           e.{{FinExemptionCertificate.issueDate}} AS issued,
           e.{{FinExemptionCertificate.expiryDate}} AS expires
    FROM {{FinExemptionCertificate}} e
    WHERE e.{{FinExemptionCertificate.active}}
)
SELECT 'MISSING' AS status, n.cust AS customerCode, n.cname AS legalName, n.ctax AS taxCode,
       n.state AS state, CAST(NULL AS varchar) AS certificateNo, CAST(NULL AS date) AS expiryDate
FROM needed n
WHERE NOT EXISTS (
    SELECT 1 FROM held h
    WHERE h.cust = n.cust AND h.state = n.state
      AND (h.issued IS NULL OR h.issued <= :onDate)
      AND (h.expires IS NULL OR h.expires >= :onDate))
UNION ALL
SELECT CASE WHEN h.expires < :onDate THEN 'EXPIRED' ELSE 'EXPIRING' END, n.cust, n.cname,
       n.ctax, n.state, h.cert, h.expires
FROM needed n
JOIN held h ON h.cust = n.cust AND h.state = n.state
WHERE h.expires IS NOT NULL
  AND h.expires <= CAST(:onDate AS date) + CAST(COALESCE(:days, 30) AS integer)
