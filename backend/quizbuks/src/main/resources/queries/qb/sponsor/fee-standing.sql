/*---
id: qb.sponsor.fee-standing
description: "Q87b: the sponsor's own fee standing for the console - billing mode, credit, what is owed, overdue"
entities: [QbSponsorBilling, QbFeeInvoice, SysParam, LedgerEntry, LedgerAccount]
datasets:
  QbSponsorBilling: urn:jabiz:dataset:sponsor:QbSponsorBilling
  QbFeeInvoice: urn:jabiz:dataset:sponsor:QbFeeInvoice
  SysParam: urn:jabiz:dataset:now:SysParam
  LedgerEntry: urn:jabiz:dataset:sponsor:LedgerEntry
results:
  billingMode: { kind: { type: text, maxLength: 10 } }
  creditLimit: { kind: { type: monetary, currency: JPY, scale: 0 } }
  unpaid:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  overdue:     { kind: { type: bool } }
permissions: [qb.sponsor.finance.read]
---*/
-- Every dataset is the caller's own: one row, as qb.system.fee-standing reads it for the processes.
SELECT
    CAST(coalesce(b.b_mode, p.p_mode, 'PREPAID') AS varchar(10)) AS billingMode,
    CAST(CASE WHEN coalesce(b.b_mode, p.p_mode, 'PREPAID') = 'POSTPAID' THEN coalesce(b.b_credit, 0) ELSE 0 END
        AS numeric) AS creditLimit,
    coalesce(l.l_unpaid, 0) AS unpaid,
    EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i WHERE i.{{QbFeeInvoice.status}} = 'OVERDUE') AS overdue
FROM (SELECT 1 AS one) base
LEFT JOIN (SELECT x.{{QbSponsorBilling.billingMode}} AS b_mode, x.{{QbSponsorBilling.creditLimit}} AS b_credit
    FROM {{QbSponsorBilling}} x) b ON true
CROSS JOIN (SELECT max(CASE WHEN s.{{SysParam.paramKey}} = 'qb.fee.default-billing-mode'
    THEN s.{{SysParam.value}} END) AS p_mode FROM {{SysParam}} s
    WHERE s.{{SysParam.paramKey}} = 'qb.fee.default-billing-mode') p
CROSS JOIN (
    SELECT sum(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS l_unpaid
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '1130') l
