/*---
id: qb.system.recon-fee-receivable
description: Whether account 1130 (platform fees receivable) agrees with the open and overdue invoices, by sponsor, and 2160 in all with the cash caches (the daily reconciliation, Q87; differences only)
entities: [QbFeeInvoice, QbFeeCharge, QbWallet, LedgerEntry, LedgerAccount]
datasets:
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbWallet: urn:jabiz:dataset:default:QbWallet
results:
  checkCode: { kind: { type: text, maxLength: 30 } }
  sponsorId: { kind: { type: text, maxLength: 128 } }
  expected:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  actual:    { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [checkCode, sponsorId]
  defaultSort: { field: checkCode, asc: true }
  key: [checkCode, sponsorId]
permissions: [qb.wallet.system]
---*/
-- One statement, one snapshot. FEES_RECEIVABLE: 1130 for a sponsor (debit less credit) against the total of their
-- invoices still owed (a charge is posted when made, paid when its invoice is; a voided one is reversed).
-- TOTAL_2160: account 2160 (credit less debit) against every cash cache.
SELECT CAST('FEES_RECEIVABLE' AS varchar(30)) AS checkCode,
       CAST(coalesce(i.i_sponsor, l.l_party) AS varchar(128)) AS sponsorId,
       coalesce(i.i_owed, 0) AS expected, coalesce(l.l_balance, 0) AS actual
FROM (
    SELECT o.o_sponsor AS i_sponsor, sum(o.o_total) AS i_owed
    FROM (
        -- F-M1: what is still owed of an invoice paid in part.
        SELECT x.{{QbFeeInvoice.sponsorId}} AS o_sponsor,
               x.{{QbFeeInvoice.total}} - x.{{QbFeeInvoice.paidAmount}} AS o_total
        FROM {{QbFeeInvoice}} x
        WHERE x.{{QbFeeInvoice.status}} IN ('OPEN', 'PARTIAL', 'OVERDUE')
        UNION ALL
        -- A postpaid charge is receivable from the day it is made; its month's invoice comes on the 2nd of the next
        -- (or its own, when the sponsor paid it early).
        SELECT c.{{QbFeeCharge.sponsorId}}, c.{{QbFeeCharge.total}}
        FROM {{QbFeeCharge}} c
        WHERE c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
          AND NOT EXISTS (
              SELECT 1 FROM {{QbFeeInvoice}} v
              WHERE v.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                AND (v.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)
                     OR (v.{{QbFeeInvoice.chargeId}} IS NULL
                         AND v.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}})))) o
    GROUP BY o.o_sponsor) i
FULL OUTER JOIN (
    SELECT e.{{LedgerEntry.dimension1}} AS l_party,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS l_balance
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '1130'
    GROUP BY e.{{LedgerEntry.dimension1}}) l ON l.l_party = i.i_sponsor
WHERE coalesce(i.i_owed, 0) <> coalesce(l.l_balance, 0)
UNION ALL
SELECT CAST('TOTAL_2160' AS varchar(30)) AS checkCode, CAST(NULL AS varchar(128)) AS sponsorId,
       l.l_total AS expected, w.w_total AS actual
FROM (
    SELECT coalesce(sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END), 0) AS l_total
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2160') l
CROSS JOIN (
    SELECT coalesce(sum(x.{{QbWallet.balance}}), 0) AS w_total
    FROM {{QbWallet}} x
    WHERE x.{{QbWallet.kind}} = 'CASH') w
WHERE l.l_total <> w.w_total
