/*---
id: finance.close.exceptions
description: >-
  What keeps a period's automatic close checks from passing (FIN-PC-004, FIN-CT-005), one row per exception and the
  check it fails: ENTRIES_POSTED, journal entries dated in the period that are drafts, submitted or approved but not
  posted, invoices and bills dated in it that are drafts or wait for approval, payment runs paying in it that are not
  released and write-offs waiting for approval; BANK_RECONCILED, active bank accounts without a signed-off
  reconciliation of a statement closing in the period; RECURRING_RUN, recurring entry and invoice templates covering
  the period without its entry or invoice; AUTO_REVERSALS, posted entries due to reverse by the period's end and not
  reversed; DEPRECIATION_RUN, the period's depreciation not run while assets are in service; CLEARING_ZERO, clearing
  and suspense accounts not at zero at the period's end (earlier years' period 13 counts). No row for a check: it passes. The rows are its evidence.
entities: [FinPeriod, FinJournal, FinInvoice, FinBill, FinPaymentRun, FinWriteOff, FinBankAccount,
  FinBankReconciliation, FinRecurringTemplate, FinRecurringInvoice, FinAsset, FinAssetDisposal, FinDepreciationRun,
  LedgerAccount, LedgerTransaction, LedgerEntry, FinAccount, FinPosting]
params:
  periodKey: { like: FinPeriod.periodKey, required: true }
results:
  checkCode:   { kind: { type: text, maxLength: 30 } }
  itemId:      { kind: { type: text, maxLength: 40 } }
  reference:   { kind: { type: text, maxLength: 60 } }
  description: { kind: { type: text, maxLength: 500 } }
  itemDate:    { from: FinPeriod.endDate }
  amount:      { from: LedgerEntry.amount }
list:
  filters: [checkCode, reference]
  sorts:   [checkCode, itemDate, reference]
  defaultSort: { field: checkCode, asc: true }
  key: [checkCode, itemId]
permissions: [fin.journal.read, fin.ar.read, fin.ap.read]
report:
  landscape: true
---*/
WITH p AS (
    SELECT fp.{{FinPeriod.periodKey}} AS pkey, fp.{{FinPeriod.startDate}} AS start_day,
           fp.{{FinPeriod.endDate}} AS end_day, COALESCE(fp.{{FinPeriod.adjustment}}, false) AS adj_period,
           fp.{{FinPeriod.fiscalYear}} AS fy
    FROM {{FinPeriod}} fp
    WHERE fp.{{FinPeriod.periodKey}} = :periodKey
),
balances AS (
    SELECT a.{{LedgerAccount.accountCode}} AS code, a.{{LedgerAccount.accountName}} AS acct_label,
           SUM(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                    ELSE -e.{{LedgerEntry.amount}} END) AS net
    FROM {{LedgerEntry}} e
    JOIN {{LedgerTransaction}} t ON t.{{LedgerTransaction.transactionId}} = e.{{LedgerEntry.transactionId}}
    JOIN {{FinPosting}} ps ON ps.{{FinPosting.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    JOIN {{FinAccount}} f ON f.{{FinAccount.ledgerAccountId}} = a.{{LedgerAccount.accountId}}
    CROSS JOIN p
    WHERE f.{{FinAccount.clearing}}
      AND ps.{{FinPosting.postingDate}} <= p.end_day
      AND (p.adj_period OR ps.{{FinPosting.periodNo}} <> 13 OR ps.{{FinPosting.fiscalYear}} <> p.fy)
    GROUP BY a.{{LedgerAccount.accountCode}}, a.{{LedgerAccount.accountName}}
)
SELECT 'ENTRIES_POSTED' AS checkCode, CAST(j.{{FinJournal.journalId}} AS text) AS itemId,
       COALESCE(j.{{FinJournal.journalNo}}, 'Draft') AS reference,
       'Journal entry ' || LOWER(j.{{FinJournal.status}}) || ', not posted: ' || COALESCE(j.{{FinJournal.description}}, '')
           AS description,
       j.{{FinJournal.postingDate}} AS itemDate, j.{{FinJournal.totalDebit}} AS amount
FROM {{FinJournal}} j CROSS JOIN p
WHERE j.{{FinJournal.status}} IN ('DRAFT', 'SUBMITTED', 'APPROVED')
  AND j.{{FinJournal.postingDate}} BETWEEN p.start_day AND p.end_day
  AND COALESCE(j.{{FinJournal.adjustmentPeriod}}, false) = p.adj_period
UNION ALL
SELECT 'ENTRIES_POSTED', CAST(i.{{FinInvoice.invoiceId}} AS text), COALESCE(i.{{FinInvoice.invoiceNo}}, 'Draft'),
       CASE WHEN i.{{FinInvoice.approval}} = 'PENDING' THEN 'Invoice waiting for approval: '
            ELSE 'Draft invoice: ' END || i.{{FinInvoice.customerCode}},
       i.{{FinInvoice.invoiceDate}}, i.{{FinInvoice.total}}
FROM {{FinInvoice}} i CROSS JOIN p
WHERE (i.{{FinInvoice.status}} = 'DRAFT' OR i.{{FinInvoice.approval}} = 'PENDING')
  AND i.{{FinInvoice.invoiceDate}} BETWEEN p.start_day AND p.end_day
  AND NOT p.adj_period
UNION ALL
SELECT 'ENTRIES_POSTED', CAST(b.{{FinBill.billId}} AS text), COALESCE(b.{{FinBill.billNo}}, 'Draft'),
       CASE WHEN b.{{FinBill.approval}} = 'PENDING' THEN 'Bill waiting for approval: '
            ELSE 'Draft bill: ' END || b.{{FinBill.vendorCode}} || ' ' || COALESCE(b.{{FinBill.vendorInvoiceNo}}, ''),
       b.{{FinBill.invoiceDate}}, b.{{FinBill.total}}
FROM {{FinBill}} b CROSS JOIN p
WHERE (b.{{FinBill.status}} = 'DRAFT' OR b.{{FinBill.approval}} = 'PENDING')
  AND b.{{FinBill.invoiceDate}} BETWEEN p.start_day AND p.end_day
  AND NOT p.adj_period
UNION ALL
SELECT 'ENTRIES_POSTED', CAST(r.{{FinPaymentRun.runId}} AS text), r.{{FinPaymentRun.runNo}},
       'Payment run ' || LOWER(r.{{FinPaymentRun.status}}) || ', not released: '
           || COALESCE(r.{{FinPaymentRun.description}}, ''),
       r.{{FinPaymentRun.paymentDate}}, r.{{FinPaymentRun.total}}
FROM {{FinPaymentRun}} r CROSS JOIN p
WHERE r.{{FinPaymentRun.status}} IN ('DRAFT', 'SUBMITTED', 'APPROVED')
  AND r.{{FinPaymentRun.paymentDate}} BETWEEN p.start_day AND p.end_day
  AND NOT p.adj_period
UNION ALL
SELECT 'ENTRIES_POSTED', CAST(w.{{FinWriteOff.writeOffId}} AS text), w.{{FinWriteOff.invoiceNo}},
       'Write-off waiting for approval: ' || w.{{FinWriteOff.customerCode}},
       w.{{FinWriteOff.writeOffDate}}, w.{{FinWriteOff.amount}}
FROM {{FinWriteOff}} w CROSS JOIN p
WHERE w.{{FinWriteOff.status}} = 'PENDING'
  AND w.{{FinWriteOff.writeOffDate}} BETWEEN p.start_day AND p.end_day
  AND NOT p.adj_period
UNION ALL
SELECT 'BANK_RECONCILED', CAST(ba.{{FinBankAccount.bankAccountId}} AS text), ba.{{FinBankAccount.bankCode}},
       'No signed-off reconciliation of ' || ba.{{FinBankAccount.bankCode}} || ' ('
           || ba.{{FinBankAccount.glAccount}} || ') for a statement closing in ' || p.pkey,
       p.end_day, CAST(NULL AS numeric)
FROM {{FinBankAccount}} ba CROSS JOIN p
WHERE ba.{{FinBankAccount.active}}
  AND NOT p.adj_period
  AND NOT EXISTS (
      SELECT 1 FROM {{FinBankReconciliation}} rec
      WHERE rec.{{FinBankReconciliation.bankCode}} = ba.{{FinBankAccount.bankCode}}
        AND rec.{{FinBankReconciliation.status}} = 'SIGNED_OFF'
        AND rec.{{FinBankReconciliation.statementDate}} BETWEEN p.start_day AND p.end_day)
UNION ALL
SELECT 'RECURRING_RUN', CAST(rt.{{FinRecurringTemplate.templateId}} AS text), rt.{{FinRecurringTemplate.templateCode}},
       'Recurring entry not made for ' || p.pkey || ': ' || COALESCE(rt.{{FinRecurringTemplate.description}}, ''),
       p.end_day, CAST(NULL AS numeric)
FROM {{FinRecurringTemplate}} rt CROSS JOIN p
WHERE rt.{{FinRecurringTemplate.active}}
  AND NOT p.adj_period
  AND rt.{{FinRecurringTemplate.startDate}} <= p.end_day
  AND (rt.{{FinRecurringTemplate.endDate}} IS NULL OR rt.{{FinRecurringTemplate.endDate}} >= p.start_day)
  AND NOT EXISTS (
      SELECT 1 FROM {{FinJournal}} rj
      WHERE rj.{{FinJournal.recurringKey}} = rt.{{FinRecurringTemplate.templateCode}} || '/' || p.pkey)
UNION ALL
SELECT 'RECURRING_RUN', CAST(ri.{{FinRecurringInvoice.templateId}} AS text), ri.{{FinRecurringInvoice.templateCode}},
       'Recurring invoice not made for ' || p.pkey || ': ' || ri.{{FinRecurringInvoice.customerCode}},
       LEAST(p.start_day + CAST(ri.{{FinRecurringInvoice.invoiceDay}} AS integer) - 1, p.end_day), CAST(NULL AS numeric)
FROM {{FinRecurringInvoice}} ri CROSS JOIN p
WHERE ri.{{FinRecurringInvoice.active}}
  AND NOT p.adj_period
  AND ri.{{FinRecurringInvoice.startDate}}
      <= LEAST(p.start_day + CAST(ri.{{FinRecurringInvoice.invoiceDay}} AS integer) - 1, p.end_day)
  AND (ri.{{FinRecurringInvoice.endDate}} IS NULL OR ri.{{FinRecurringInvoice.endDate}}
      >= LEAST(p.start_day + CAST(ri.{{FinRecurringInvoice.invoiceDay}} AS integer) - 1, p.end_day))
  AND NOT EXISTS (
      SELECT 1 FROM {{FinInvoice}} rv
      WHERE rv.{{FinInvoice.recurringKey}} = ri.{{FinRecurringInvoice.templateCode}} || '/' || p.pkey)
UNION ALL
SELECT 'AUTO_REVERSALS', CAST(ar.{{FinJournal.journalId}} AS text), ar.{{FinJournal.journalNo}},
       'Due to reverse on ' || ar.{{FinJournal.autoReverseDate}} || ', not reversed: '
           || COALESCE(ar.{{FinJournal.description}}, ''),
       ar.{{FinJournal.autoReverseDate}}, ar.{{FinJournal.totalDebit}}
FROM {{FinJournal}} ar CROSS JOIN p
WHERE ar.{{FinJournal.status}} = 'POSTED'
  AND ar.{{FinJournal.autoReverseDate}} <= p.end_day
  AND ar.{{FinJournal.reversedById}} IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM {{FinJournal}} rr
      WHERE rr.{{FinJournal.reversesJournalId}} = ar.{{FinJournal.journalId}}
        AND rr.{{FinJournal.status}} = 'POSTED')
UNION ALL
SELECT 'DEPRECIATION_RUN', p.pkey, p.pkey,
       'No depreciation run for ' || p.pkey || ' while assets are in service',
       p.end_day, CAST(NULL AS numeric)
FROM p
WHERE NOT p.adj_period
  AND EXISTS (
      SELECT 1 FROM {{FinAsset}} fa
      WHERE fa.{{FinAsset.inServiceDate}} <= p.end_day
        AND COALESCE(fa.{{FinAsset.active}}, true)
        AND NOT EXISTS (
            SELECT 1 FROM {{FinAssetDisposal}} d
            WHERE d.{{FinAssetDisposal.assetId}} = fa.{{FinAsset.assetId}}
              AND d.{{FinAssetDisposal.disposalDate}} < p.start_day))
  AND NOT EXISTS (
      SELECT 1 FROM {{FinDepreciationRun}} dr
      WHERE dr.{{FinDepreciationRun.periodKey}} = p.pkey
        AND dr.{{FinDepreciationRun.status}} = 'POSTED')
UNION ALL
SELECT 'CLEARING_ZERO', balances.code, balances.code,
       balances.acct_label || ' is not at zero', p.end_day, balances.net
FROM balances CROSS JOIN p
WHERE balances.net <> 0
