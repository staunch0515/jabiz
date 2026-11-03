/*---
id: qb.operator.sponsor-billing
description: "Q87b: the billing mode and credit of the caller operator's sponsors, with what each owes"
entities: [QbSponsorOperator, QbOperatorMember, QbSponsorBilling, QbSponsor, SysParam, LedgerEntry, LedgerAccount]
datasets:
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
  QbSponsorBilling: urn:jabiz:dataset:default:QbSponsorBilling
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  sponsorId:   { from: QbSponsorOperator.userId }
  company:     { from: QbSponsor.company }
  billingMode: { kind: { type: text, maxLength: 10 } }
  own:         { kind: { type: bool } }
  creditLimit: { kind: { type: monetary, currency: JPY, scale: 0 } }
  unpaid:      { kind: { type: monetary, currency: JPY, scale: 0 } }
  reason:      { from: QbSponsorBilling.reason }
  updatedAt:   { from: QbSponsorBilling.updatedAt }
list:
  sorts: [company, unpaid]
  defaultSort: { field: company, asc: true }
  key: [sponsorId]
permissions: [qb.operator.use]
---*/
-- Only through the caller's current membership, as the other operator templates; own: the sponsor has a setting of
-- its own (else the platform's default applies).
SELECT a.{{QbSponsorOperator.userId}} AS sponsorId, s.{{QbSponsor.company}} AS company,
       CAST(coalesce(b.{{QbSponsorBilling.billingMode}}, p.p_mode, 'PREPAID') AS varchar(10)) AS billingMode,
       b.{{QbSponsorBilling.sponsorId}} IS NOT NULL AS own,
       CAST(coalesce(b.{{QbSponsorBilling.creditLimit}}, 0) AS numeric) AS creditLimit,
       coalesce((SELECT sum(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
                 ELSE -e.{{LedgerEntry.amount}} END)
            FROM {{LedgerEntry}} e
            JOIN {{LedgerAccount}} la ON la.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
            WHERE la.{{LedgerAccount.accountCode}} = '1130'
              AND e.{{LedgerEntry.dimension1}} = a.{{QbSponsorOperator.userId}}), 0) AS unpaid,
       b.{{QbSponsorBilling.reason}} AS reason, b.{{QbSponsorBilling.updatedAt}} AS updatedAt
FROM {{QbSponsorOperator}} a
JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
  AND me.{{QbOperatorMember.operatorId}} = a.{{QbSponsorOperator.operatorId}}
LEFT JOIN {{QbSponsor}} s ON s.{{QbSponsor.userId}} = a.{{QbSponsorOperator.userId}}
LEFT JOIN {{QbSponsorBilling}} b ON b.{{QbSponsorBilling.sponsorId}} = a.{{QbSponsorOperator.userId}}
CROSS JOIN (SELECT max(CASE WHEN x.{{SysParam.paramKey}} = 'qb.fee.default-billing-mode'
    THEN x.{{SysParam.value}} END) AS p_mode FROM {{SysParam}} x
    WHERE x.{{SysParam.paramKey}} = 'qb.fee.default-billing-mode') p
