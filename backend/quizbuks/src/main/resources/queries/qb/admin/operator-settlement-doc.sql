/*---
id: qb.admin.operator-settlement-doc
description: One operator's settlement as its statement shows it (Q48b, F-M12, the document qb.operator_settlement)
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
  platformFees:    { from: QbOperatorSettlement.platformFees }
  costExcluded:    { from: QbOperatorSettlement.costExcluded }
  giftPaid:        { from: QbOperatorSettlement.giftPaid }
  platformShare:   { kind: { type: text, maxLength: 10 } }
  operatorShare:   { from: QbOperatorSettlement.operatorShare }
  prospectingCost: { from: QbOperatorSettlement.prospectingCost }
  creditNoteDeduction: { from: QbOperatorSettlement.creditNoteDeduction }
  giftShareDeduction:  { from: QbOperatorSettlement.giftShareDeduction }
  voidDeduction:   { from: QbOperatorSettlement.voidDeduction }
  giftDue:         { from: QbOperatorSettlement.giftDue }
  giftRecovered:   { from: QbOperatorSettlement.giftRecovered }
  giftCarriedIn:   { from: QbOperatorSettlement.giftCarriedIn }
  giftCarriedOut:  { from: QbOperatorSettlement.giftCarriedOut }
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
    s.{{QbOperatorSettlement.platformFees}} AS platformFees,
    s.{{QbOperatorSettlement.costExcluded}} AS costExcluded,
    s.{{QbOperatorSettlement.giftPaid}} AS giftPaid,
    CAST(trim(to_char(s.{{QbOperatorSettlement.platformShare}} * 100, 'FM990.99')) || '%' AS varchar(10)) AS platformShare,
    s.{{QbOperatorSettlement.operatorShare}} AS operatorShare,
    s.{{QbOperatorSettlement.prospectingCost}} AS prospectingCost,
    s.{{QbOperatorSettlement.creditNoteDeduction}} AS creditNoteDeduction,
    s.{{QbOperatorSettlement.giftShareDeduction}} AS giftShareDeduction,
    s.{{QbOperatorSettlement.voidDeduction}} AS voidDeduction,
    s.{{QbOperatorSettlement.giftDue}} AS giftDue,
    s.{{QbOperatorSettlement.giftRecovered}} AS giftRecovered,
    s.{{QbOperatorSettlement.giftCarriedIn}} AS giftCarriedIn,
    s.{{QbOperatorSettlement.giftCarriedOut}} AS giftCarriedOut,
    s.{{QbOperatorSettlement.plan}} AS plan,
    s.{{QbOperatorSettlement.fixedFee}} AS fixedFee,
    s.{{QbOperatorSettlement.carriedIn}} AS carriedIn,
    s.{{QbOperatorSettlement.carriedOut}} AS carriedOut,
    s.{{QbOperatorSettlement.payable}} AS payable
FROM {{QbOperatorSettlement}} s
JOIN {{QbOperator}} o ON o.{{QbOperator.operatorId}} = s.{{QbOperatorSettlement.operatorId}}
WHERE s.{{QbOperatorSettlement.settlementId}} = :settlementId
