/*---
id: finance.gl.prior_period_items
description: >-
  Prior-period items (FIN-PC-007): the journal entries, bills and invoices booked in a period later than the period
  of their document date, with both dates, both periods and the document period's state, for the entries booked
  between two days. Such an item is booked in an open period because its own was closed (or the document came late);
  its document date keeps the reference to the period it belongs to. A voided document stays listed with its state.
entities: [FinPeriod, FinJournal, FinBill, FinInvoice]
params:
  from: { like: FinJournal.postingDate, required: true, description: the first day of the bookings }
  to:   { like: FinJournal.postingDate, required: true, description: the last day of the bookings }
results:
  source:         { kind: { type: text, maxLength: 10 } }
  documentNo:     { kind: { type: text, maxLength: 40 } }
  party:          { kind: { type: text, maxLength: 20 } }
  documentDate:   { from: FinJournal.documentDate }
  documentPeriod: { from: FinPeriod.periodKey }
  periodStatus:   { from: FinPeriod.status }
  postingDate:    { from: FinJournal.postingDate }
  postingPeriod:  { from: FinPeriod.periodKey }
  amount:         { from: FinJournal.totalDebit }
  status:         { kind: { type: text, maxLength: 12 } }
  description:    { kind: { type: text, maxLength: 500 } }
list:
  filters: [source, documentNo, party, documentPeriod, postingPeriod]
  sorts:   [postingDate, documentDate, documentNo]
  defaultSort: { field: postingDate, asc: true }
  key: [source, documentNo]
permissions: [fin.journal.read, fin.ar.read, fin.ap.read]
report:
  landscape: true
  period: { from: from, to: to }
---*/
WITH regular AS (
    SELECT rp.{{FinPeriod.periodKey}} AS pkey, rp.{{FinPeriod.startDate}} AS first_day,
           rp.{{FinPeriod.endDate}} AS last_day, rp.{{FinPeriod.status}} AS state
    FROM {{FinPeriod}} rp
    WHERE NOT COALESCE(rp.{{FinPeriod.adjustment}}, false) AND NOT COALESCE(rp.{{FinPeriod.opening}}, false)
),
items AS (
    SELECT 'JOURNAL' AS src, j.{{FinJournal.journalNo}} AS doc_no, CAST(NULL AS text) AS party_code,
           j.{{FinJournal.documentDate}} AS doc_day, j.{{FinJournal.postingDate}} AS booked_day,
           j.{{FinJournal.totalDebit}} AS usd_total, j.{{FinJournal.description}} AS memo,
           j.{{FinJournal.status}} AS doc_state
    FROM {{FinJournal}} j
    WHERE j.{{FinJournal.status}} = 'POSTED' AND j.{{FinJournal.documentDate}} IS NOT NULL
      AND NOT COALESCE(j.{{FinJournal.adjustmentPeriod}}, false)
      AND j.{{FinJournal.postingDate}} BETWEEN :from AND :to
    UNION ALL
    SELECT 'BILL', b.{{FinBill.billNo}}, b.{{FinBill.vendorCode}}, b.{{FinBill.invoiceDate}},
           b.{{FinBill.postingDate}}, COALESCE(b.{{FinBill.totalUsd}}, b.{{FinBill.total}}),
           COALESCE(b.{{FinBill.description}}, b.{{FinBill.vendorInvoiceNo}}), b.{{FinBill.status}}
    FROM {{FinBill}} b
    WHERE b.{{FinBill.status}} <> 'DRAFT' AND b.{{FinBill.postingDate}} BETWEEN :from AND :to
    UNION ALL
    SELECT 'INVOICE', i.{{FinInvoice.invoiceNo}}, i.{{FinInvoice.customerCode}}, i.{{FinInvoice.invoiceDate}},
           i.{{FinInvoice.postingDate}}, COALESCE(i.{{FinInvoice.totalUsd}}, i.{{FinInvoice.total}}),
           i.{{FinInvoice.description}}, i.{{FinInvoice.status}}
    FROM {{FinInvoice}} i
    WHERE i.{{FinInvoice.status}} <> 'DRAFT' AND i.{{FinInvoice.postingDate}} BETWEEN :from AND :to
)
SELECT items.src AS source, items.doc_no AS documentNo, items.party_code AS party, items.doc_day AS documentDate,
       dp.pkey AS documentPeriod, dp.state AS periodStatus, items.booked_day AS postingDate,
       bp.pkey AS postingPeriod, items.usd_total AS amount, items.doc_state AS status, items.memo AS description
FROM items
JOIN regular dp ON items.doc_day BETWEEN dp.first_day AND dp.last_day
JOIN regular bp ON items.booked_day BETWEEN bp.first_day AND bp.last_day
WHERE dp.last_day < bp.first_day
