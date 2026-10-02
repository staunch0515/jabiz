/*---
id: finance.bank.book_items
description: >-
  The book items of a bank account not matched to its statement (FIN-BK-004, 005, 009): each ledger transaction on
  its cash account with the amount it moved there (receipts positive), its document, the customer or payee and, for a
  payment, its check number and run; and the outstanding items brought over at the cutover. Only what was posted
  after the cutover: the opening entry and anything before are the cutover's items. A payment's reversal (a void) is
  money back, not a payment: it names no payee, check or run. What matching works on, and the outstanding items of
  a reconciliation.
entities: [FinBankAccount, LedgerAccount, LedgerTransaction, LedgerEntry, FinPosting, FinPayment, FinReceipt,
  FinCustomer, FinBankOpening, FinBankOpeningItem, FinBankMatch, FinBankMatchItem]
params:
  bankCode: { like: FinBankAccount.bankCode, required: true }
  to:       { like: FinPosting.postingDate, description: "the last day of the items; all when not given" }
results:
  refKind:    { from: FinBankMatchItem.refKind }
  refId:      { from: FinBankMatchItem.refId }
  itemDate:   { from: FinPosting.postingDate }
  documentNo: { from: FinPosting.documentNo }
  description: { from: LedgerTransaction.description }
  amount:     { from: LedgerEntry.amount }
  party:      { from: FinPayment.payee }
  checkNo:    { from: FinPayment.checkNo }
  runNo:      { from: FinPayment.runNo }
list:
  filters: [refKind, refId, itemDate, documentNo, amount, party, checkNo, runNo]
  sorts:   [itemDate, documentNo, amount]
  defaultSort: { field: itemDate, asc: true }
  key: [refKind, refId]
permissions: [fin.bank.activity.read]
---*/
WITH bank AS (
    SELECT b.{{FinBankAccount.glAccount}} AS gl
    FROM {{FinBankAccount}} b
    WHERE b.{{FinBankAccount.bankCode}} = :bankCode
),
cutover AS (
    SELECT o.{{FinBankOpening.cutoverDate}} AS cutover_day
    FROM {{FinBankOpening}} o
    WHERE o.{{FinBankOpening.bankCode}} = :bankCode
),
cash AS (
    SELECT a.{{LedgerAccount.accountId}} AS account_key
    FROM {{LedgerAccount}} a
    JOIN bank ON bank.gl = a.{{LedgerAccount.accountCode}}
),
matched_refs AS (
    -- Matched: in a match that was not undone.
    SELECT i.{{FinBankMatchItem.refId}} AS ref_key
    FROM {{FinBankMatchItem}} i
    JOIN {{FinBankMatch}} m ON m.{{FinBankMatch.matchId}} = i.{{FinBankMatchItem.matchId}}
    WHERE i.{{FinBankMatchItem.bankCode}} = :bankCode
      AND i.{{FinBankMatchItem.side}} = 'BOOK'
      AND NOT EXISTS (SELECT 1 FROM {{FinBankMatch}} u
                      WHERE u.{{FinBankMatch.reversesMatchId}} = m.{{FinBankMatch.matchId}})
),
ledger AS (
    SELECT CAST(fp.{{FinPosting.transactionId}} AS varchar) AS ref_key,
           fp.{{FinPosting.postingDate}} AS on_day,
           fp.{{FinPosting.documentNo}} AS doc_no,
           fp.{{FinPosting.sourceEntity}} AS src_entity,
           fp.{{FinPosting.sourceId}} AS src_key,
           t.{{LedgerTransaction.description}} AS description,
           SUM(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                    ELSE -e.{{LedgerEntry.amount}} END) AS amount
    FROM {{LedgerEntry}} e
    JOIN cash ON cash.account_key = e.{{LedgerEntry.accountId}}
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPosting}} fp ON fp.{{FinPosting.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN cutover ON fp.{{FinPosting.postingDate}} > cutover.cutover_day
    WHERE (CAST(:to AS date) IS NULL OR fp.{{FinPosting.postingDate}} <= :to)
    GROUP BY fp.{{FinPosting.transactionId}}, fp.{{FinPosting.postingDate}}, fp.{{FinPosting.documentNo}},
             fp.{{FinPosting.sourceEntity}}, fp.{{FinPosting.sourceId}}, t.{{LedgerTransaction.description}}
    HAVING SUM(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                    ELSE -e.{{LedgerEntry.amount}} END) <> 0
)
SELECT 'LEDGER' AS refKind, l.ref_key AS refId, l.on_day AS itemDate, l.doc_no AS documentNo,
       l.description AS description, l.amount AS amount,
       COALESCE(p.{{FinPayment.payee}}, c.{{FinCustomer.legalName}}) AS party,
       p.{{FinPayment.checkNo}} AS checkNo, p.{{FinPayment.runNo}} AS runNo
FROM ledger l
LEFT JOIN {{FinPayment}} p
  ON l.src_entity = 'FinPayment' AND l.amount < 0 AND CAST(p.{{FinPayment.paymentId}} AS varchar) = l.src_key
LEFT JOIN {{FinReceipt}} r
  ON l.src_entity = 'FinReceipt' AND CAST(r.{{FinReceipt.receiptId}} AS varchar) = l.src_key
LEFT JOIN {{FinCustomer}} c ON c.{{FinCustomer.customerCode}} = r.{{FinReceipt.customerCode}}
WHERE l.ref_key NOT IN (SELECT ref_key FROM matched_refs)
UNION ALL
SELECT 'OPENING', CAST(o.{{FinBankOpeningItem.itemId}} AS varchar), o.{{FinBankOpeningItem.itemDate}},
       o.{{FinBankOpeningItem.reference}}, o.{{FinBankOpeningItem.description}}, o.{{FinBankOpeningItem.amount}},
       NULL, NULL, NULL
FROM {{FinBankOpeningItem}} o
WHERE o.{{FinBankOpeningItem.bankCode}} = :bankCode
  AND (CAST(:to AS date) IS NULL OR o.{{FinBankOpeningItem.itemDate}} <= :to)
  AND CAST(o.{{FinBankOpeningItem.itemId}} AS varchar) NOT IN (SELECT ref_key FROM matched_refs)
