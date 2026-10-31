/*---
id: qb.admin.operator-settlement-doc
description: One operator's settlement as its statement shows it (Q48b, the document qb.operator_settlement)
entities: [QbOperatorSettlement, QbOperator]
datasets:
  QbOperatorSettlement: urn:jabiz:dataset:default:QbOperatorSettlement
  QbOperator: urn:jabiz:dataset:default:QbOperator
params:
  settlementId: { like: QbOperatorSettlement.settlementId, required: true, description: The settlement }
results:
  settlementNo:    { from: QbOperatorSettlement.settlementId }
  operatorName:    { from: QbOperator.name }
  contactName:     { from: QbOperator.contactName }
  month:           { from: QbOperatorSettlement.month }
  revenue:         { from: QbOperatorSettlement.revenue }
  platformShare:   { kind: { type: text, maxLength: 10 } }
  operatorShare:   { from: QbOperatorSettlement.operatorShare }
  prospectingCost: { from: QbOperatorSettlement.prospectingCost }
  plan: { from: QbOperatorSettlement.plan }
  fixedFee: { from: QbOperatorSettlement.fixedFee }
  carriedIn:       { from: QbOperatorSettlement.carriedIn }
  carriedOut:      { from: QbOperatorSettlement.carriedOut }
  payable:         { from: QbOperatorSettlement.payable }
permissions: [qb.admin.finance.read]
---*/
-- The share as a percentage, written once here so the statement reads the same in every language.
SELECT
    s.{{QbOperatorSettlement.settlementId}} AS settlementNo,
    o.{{QbOperator.name}} AS operatorName,
    o.{{QbOperator.contactName}} AS contactName,
    s.{{QbOperatorSettlement.month}} AS month,
    s.{{QbOperatorSettlement.revenue}} AS revenue,
    CAST(trim(to_char(s.{{QbOperatorSettlement.platformShare}} * 100, 'FM990.99')) || '%' AS varchar(10)) AS platformShare,
    s.{{QbOperatorSettlement.operatorShare}} AS operatorShare,
    s.{{QbOperatorSettlement.prospectingCost}} AS prospectingCost,
    s.{{QbOperatorSettlement.plan}} AS plan,
    s.{{QbOperatorSettlement.fixedFee}} AS fixedFee,
    s.{{QbOperatorSettlement.carriedIn}} AS carriedIn,
    s.{{QbOperatorSettlement.carriedOut}} AS carriedOut,
    s.{{QbOperatorSettlement.payable}} AS payable
FROM {{QbOperatorSettlement}} s
JOIN {{QbOperator}} o ON o.{{QbOperator.operatorId}} = s.{{QbOperatorSettlement.operatorId}}
WHERE s.{{QbOperatorSettlement.settlementId}} = :settlementId
