/*---
id: qb.system.fee-standing
description: "Q87b: a sponsor's fee standing as the processes read it - billing mode and credit (the sponsor's, else the platform's default), what is owed (account 1130 for them), whether an invoice is overdue, and the overdue switch"
entities: [QbSponsorBilling, QbFeeInvoice, SysParam, LedgerEntry, LedgerAccount]
datasets:
  QbSponsorBilling: urn:jabiz:dataset:default:QbSponsorBilling
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  SysParam: urn:jabiz:dataset:now:SysParam
params:
  sponsorId: { like: QbSponsorBilling.sponsorId, required: true, description: The sponsor }
results:
  billingMode:  { kind: { type: text, maxLength: 10 } }
  creditLimit:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  unpaid:       { kind: { type: monetary, currency: JPY, scale: 0 } }
  overdue:      { kind: { type: bool } }
  blockOverdue: { kind: { type: bool } }
permissions: [qb.fee.system]
---*/
-- One row, one statement. What is owed is the ledger's (receivable charges, invoiced or not, less what was paid);
-- a parameter not yet published reads as declared (PREPAID, the overdue block on).
SELECT
    CAST(coalesce(b.b_mode, p.p_mode, 'PREPAID') AS varchar(10)) AS billingMode,
    CAST(CASE WHEN coalesce(b.b_mode, p.p_mode, 'PREPAID') = 'POSTPAID' THEN coalesce(b.b_credit, 0) ELSE 0 END
        AS numeric) AS creditLimit,
    coalesce(l.l_unpaid, 0) AS unpaid,
    EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i WHERE i.{{QbFeeInvoice.sponsorId}} = :sponsorId
        AND i.{{QbFeeInvoice.status}} = 'OVERDUE') AS overdue,
    coalesce(p.p_block, true) AS blockOverdue
FROM (SELECT 1 AS one) base
LEFT JOIN (
    SELECT x.{{QbSponsorBilling.billingMode}} AS b_mode, x.{{QbSponsorBilling.creditLimit}} AS b_credit
    FROM {{QbSponsorBilling}} x WHERE x.{{QbSponsorBilling.sponsorId}} = :sponsorId) b ON true
CROSS JOIN (
    SELECT max(CASE WHEN s.{{SysParam.paramKey}} = 'qb.fee.default-billing-mode' THEN s.{{SysParam.value}} END)
               AS p_mode,
           bool_or(CASE WHEN s.{{SysParam.paramKey}} = 'qb.fee.overdue.block-submit'
               THEN s.{{SysParam.value}} = 'true' END) AS p_block
    FROM {{SysParam}} s
    WHERE s.{{SysParam.paramKey}} IN ('qb.fee.default-billing-mode', 'qb.fee.overdue.block-submit')) p
CROSS JOIN (
    SELECT sum(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS l_unpaid
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '1130'
      AND e.{{LedgerEntry.dimension1}} = :sponsorId) l
