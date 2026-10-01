/*---
id: finance.ar.statement
description: >-
  A customer's statement for a period (FIN-AR-009): the balance owed before it, each invoice, credit memo, void,
  receipt, discount, write-off, recovery and refund in it with the running balance, and the balance at its end, in US dollars.
  A receipt counts when it comes in, whether or not it is applied yet, so the balance is what the customer owes less
  their unapplied cash; applying it moves nothing. Run with knownAt, the books as recorded at that time. The open-item
  statement is the aging for the customer (finance.ar.aging).
entities: [FinInvoice, FinApplication, FinReceipt]
params:
  customerCode: { like: FinInvoice.customerCode, required: true }
  from:         { like: FinApplication.applicationDate, required: true, description: the first day of the period }
  to:           { like: FinApplication.applicationDate, required: true, description: the last day of the period }
results:
  seq:         { kind: { type: numeric, precision: 9, scale: 0 } }
  entryDate:   { from: FinApplication.applicationDate }
  entry:       { kind: { type: text, maxLength: 12 } }
  documentNo:  { from: FinInvoice.invoiceNo }
  reference:   { from: FinInvoice.reference }
  description: { from: FinInvoice.description }
  charges:     { from: FinInvoice.totalUsd }
  credits:     { from: FinInvoice.totalUsd }
  balance:     { from: FinInvoice.totalUsd }
list:
  filters: [entry, documentNo]
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [seq]
permissions: [fin.ar.read]
report:
  period: { from: from, to: to }
---*/
WITH moves AS (
    -- Documents on their dates, credit memos down.
    SELECT i.{{FinInvoice.invoiceDate}} AS on_day, 1 AS ord, i.{{FinInvoice.kind}} AS what,
           i.{{FinInvoice.invoiceNo}} AS doc_no, i.{{FinInvoice.reference}} AS ref, i.{{FinInvoice.description}} AS txt,
           CASE WHEN i.{{FinInvoice.kind}} = 'CREDIT_MEMO' THEN -1 ELSE 1 END * i.{{FinInvoice.totalUsd}} AS amt
    FROM {{FinInvoice}} i
    WHERE i.{{FinInvoice.customerCode}} = :customerCode
      AND i.{{FinInvoice.status}} IN ('POSTED', 'VOID', 'WRITTEN_OFF')
    UNION ALL
    -- Voids take them back on their own dates.
    SELECT i.{{FinInvoice.voidDate}}, 2, 'VOID', i.{{FinInvoice.invoiceNo}}, i.{{FinInvoice.reference}},
           i.{{FinInvoice.voidReason}},
           CASE WHEN i.{{FinInvoice.kind}} = 'CREDIT_MEMO' THEN 1 ELSE -1 END * i.{{FinInvoice.totalUsd}}
    FROM {{FinInvoice}} i
    WHERE i.{{FinInvoice.customerCode}} = :customerCode AND i.{{FinInvoice.status}} = 'VOID'
    UNION ALL
    SELECT r.{{FinReceipt.receiptDate}}, 3, 'RECEIPT', r.{{FinReceipt.receiptNo}}, r.{{FinReceipt.reference}},
           r.{{FinReceipt.description}}, -r.{{FinReceipt.amount}}
    FROM {{FinReceipt}} r
    WHERE r.{{FinReceipt.customerCode}} = :customerCode
    UNION ALL
    SELECT r.{{FinReceipt.voidDate}}, 4, 'RECEIPT_VOID', r.{{FinReceipt.receiptNo}}, r.{{FinReceipt.reference}},
           r.{{FinReceipt.voidReason}}, r.{{FinReceipt.amount}}
    FROM {{FinReceipt}} r
    WHERE r.{{FinReceipt.customerCode}} = :customerCode AND r.{{FinReceipt.status}} = 'VOID'
    UNION ALL
    -- Discounts, write-offs and recoveries change what is owed; applying cash or credits does not.
    SELECT a.{{FinApplication.applicationDate}}, 5, 'DISCOUNT', a.{{FinApplication.sourceNo}}, NULL,
           a.{{FinApplication.reason}}, -a.{{FinApplication.discount}}
    FROM {{FinApplication}} a
    WHERE a.{{FinApplication.customerCode}} = :customerCode AND a.{{FinApplication.discount}} IS NOT NULL
    UNION ALL
    SELECT a.{{FinApplication.applicationDate}}, 6, a.{{FinApplication.sourceKind}}, a.{{FinApplication.sourceNo}},
           NULL, a.{{FinApplication.reason}}, -a.{{FinApplication.amountUsd}}
    FROM {{FinApplication}} a
    WHERE a.{{FinApplication.customerCode}} = :customerCode
      AND a.{{FinApplication.sourceKind}} IN ('WRITE_OFF', 'RECOVERY')
    UNION ALL
    -- A credit paid out raises what is owed back to where it was.
    SELECT a.{{FinApplication.applicationDate}}, 7, 'REFUND', a.{{FinApplication.sourceNo}}, NULL,
           a.{{FinApplication.reason}}, a.{{FinApplication.amountUsd}}
    FROM {{FinApplication}} a
    WHERE a.{{FinApplication.customerCode}} = :customerCode AND a.{{FinApplication.sourceKind}} = 'REFUND'
),
opening AS (
    SELECT COALESCE(SUM(amt), 0) AS bal FROM moves WHERE on_day < :from
),
period AS (
    SELECT m.*, ROW_NUMBER() OVER (ORDER BY m.on_day, m.ord, m.doc_no) AS n
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
