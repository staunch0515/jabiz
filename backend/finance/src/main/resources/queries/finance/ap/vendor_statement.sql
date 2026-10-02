/*---
id: finance.ap.vendor_statement
description: >-
  What the company owes a vendor over a period (the payables statement, to reconcile with the vendor's own): the
  balance before it, each bill, vendor credit and void, each payment and discount taken and each prepayment applied,
  with the running balance, and the balance at its end. Applying a vendor credit moves nothing: the credit counted
  when posted. The balance at a day equals the vendor's total in finance.ap.aging. Run with knownAt, as recorded then.
entities: [FinBill, FinApApplication]
params:
  vendorCode: { like: FinBill.vendorCode, required: true }
  from:       { like: FinApApplication.applicationDate, required: true, description: the first day of the period }
  to:         { like: FinApApplication.applicationDate, required: true, description: the last day of the period }
results:
  seq:         { kind: { type: numeric, precision: 9, scale: 0 } }
  entryDate:   { from: FinApApplication.applicationDate }
  entry:       { kind: { type: text, maxLength: 12 } }
  documentNo:  { from: FinBill.billNo }
  reference:   { from: FinBill.vendorInvoiceNo }
  description: { from: FinBill.description }
  charges:     { from: FinBill.total }
  credits:     { from: FinBill.total }
  balance:     { from: FinBill.total }
list:
  filters: [entry, documentNo]
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [seq]
permissions: [fin.ap.read]
report:
  period: { from: from, to: to }
---*/
WITH moves AS (
    -- Bills raise what is owed and vendor credits lower it, on the days they are booked (FIN-PC-007).
    SELECT COALESCE(b.{{FinBill.postingDate}}, b.{{FinBill.invoiceDate}}) AS on_day, 1 AS ord, b.{{FinBill.kind}} AS what, b.{{FinBill.billNo}} AS doc_no,
           b.{{FinBill.vendorInvoiceNo}} AS ref, b.{{FinBill.description}} AS txt,
           CASE WHEN b.{{FinBill.kind}} = 'CREDIT' THEN -1 ELSE 1 END * b.{{FinBill.total}} AS amt,
           b.{{FinBill.billId}} AS tie
    FROM {{FinBill}} b
    WHERE b.{{FinBill.vendorCode}} = :vendorCode AND b.{{FinBill.status}} IN ('POSTED', 'VOID')
    UNION ALL
    -- A void takes the document back on its own date.
    SELECT b.{{FinBill.voidDate}}, 2, 'VOID', b.{{FinBill.billNo}}, b.{{FinBill.vendorInvoiceNo}},
           b.{{FinBill.voidReason}},
           CASE WHEN b.{{FinBill.kind}} = 'CREDIT' THEN 1 ELSE -1 END * b.{{FinBill.total}}, b.{{FinBill.billId}}
    FROM {{FinBill}} b
    WHERE b.{{FinBill.vendorCode}} = :vendorCode AND b.{{FinBill.status}} = 'VOID'
    UNION ALL
    -- Payments and prepayments applied pay it down (a voided payment's reversal brings it back up); the reference is
    -- the bill paid, so a payment of several bills reads as one row per bill.
    SELECT a.{{FinApApplication.applicationDate}}, 3, a.{{FinApApplication.sourceKind}}, a.{{FinApApplication.sourceNo}},
           pb.{{FinBill.billNo}}, a.{{FinApApplication.reason}}, -a.{{FinApApplication.amount}},
           a.{{FinApApplication.applicationId}}
    FROM {{FinApApplication}} a
    JOIN {{FinBill}} pb ON pb.{{FinBill.billId}} = a.{{FinApApplication.billId}}
    WHERE a.{{FinApApplication.vendorCode}} = :vendorCode
      AND a.{{FinApApplication.sourceKind}} IN ('PAYMENT', 'PREPAYMENT')
    UNION ALL
    SELECT a.{{FinApApplication.applicationDate}}, 4, 'DISCOUNT', a.{{FinApApplication.sourceNo}}, db.{{FinBill.billNo}},
           a.{{FinApApplication.reason}}, -a.{{FinApApplication.discount}}, a.{{FinApApplication.applicationId}}
    FROM {{FinApApplication}} a
    JOIN {{FinBill}} db ON db.{{FinBill.billId}} = a.{{FinApApplication.billId}}
    WHERE a.{{FinApApplication.vendorCode}} = :vendorCode AND a.{{FinApApplication.discount}} IS NOT NULL
),
opening AS (
    SELECT COALESCE(SUM(amt), 0) AS bal FROM moves WHERE on_day < :from
),
period AS (
    -- The id breaks ties, so an issued statement numbers its rows the same each time it is run.
    SELECT m.*, ROW_NUMBER() OVER (ORDER BY m.on_day, m.ord, m.doc_no, m.ref, m.tie) AS n
    FROM moves m
    WHERE m.on_day >= :from AND m.on_day <= :to
)
SELECT 0 AS seq, CAST(:from AS date) AS entryDate, 'OPENING' AS entry, CAST(NULL AS varchar) AS documentNo,
       CAST(NULL AS varchar) AS reference, CAST(NULL AS varchar) AS description, CAST(NULL AS numeric) AS charges,
       CAST(NULL AS numeric) AS credits, o.bal AS balance
FROM opening o
UNION ALL
SELECT p.n, p.on_day, p.what, p.doc_no, p.ref, p.txt,
       CASE WHEN p.amt > 0 THEN p.amt END, CASE WHEN p.amt < 0 THEN -p.amt END,
       o.bal + SUM(p.amt) OVER (ORDER BY p.n)
FROM period p CROSS JOIN opening o
UNION ALL
SELECT 999999999, CAST(:to AS date), 'CLOSING', NULL, NULL, NULL, NULL, NULL,
       o.bal + COALESCE((SELECT SUM(amt) FROM period), 0)
FROM opening o
