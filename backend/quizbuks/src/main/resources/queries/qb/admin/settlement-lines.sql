/*---
id: qb.admin.settlement-lines
description: "An operator settlement's lines (F-M12 §6): each fee counted, each voided fee, each part of a payment made with granted credit, by Sponsor and kind"
entities: [QbOperatorSettlementLine]
datasets:
  QbOperatorSettlementLine: urn:jabiz:dataset:default:QbOperatorSettlementLine
params:
  settlementId: { like: QbOperatorSettlementLine.settlementId, description: Only this settlement's lines }
  operatorId:   { like: QbOperatorSettlementLine.operatorId, description: Only this operator's }
  month:        { like: QbOperatorSettlementLine.month, description: Only this month's }
results:
  lineId:       { from: QbOperatorSettlementLine.lineId }
  settlementId: { from: QbOperatorSettlementLine.settlementId }
  operatorId:   { from: QbOperatorSettlementLine.operatorId }
  month:        { from: QbOperatorSettlementLine.month }
  lineKind:     { from: QbOperatorSettlementLine.lineKind }
  sponsorId:    { from: QbOperatorSettlementLine.sponsorId }
  chargeId:     { from: QbOperatorSettlementLine.chargeId }
  feeKind:      { from: QbOperatorSettlementLine.feeKind }
  period:       { from: QbOperatorSettlementLine.period }
  late:         { from: QbOperatorSettlementLine.late }
  amount:       { from: QbOperatorSettlementLine.amount }
  excluded:     { from: QbOperatorSettlementLine.excluded }
  giftGross:    { from: QbOperatorSettlementLine.giftGross }
  grantSource:  { from: QbOperatorSettlementLine.grantSource }
  inBase:       { from: QbOperatorSettlementLine.inBase }
  recoverGross: { from: QbOperatorSettlementLine.recoverGross }
  noShare:      { from: QbOperatorSettlementLine.noShare }
  shareRate:    { from: QbOperatorSettlementLine.shareRate }
  recoveryRate: { from: QbOperatorSettlementLine.recoveryRate }
  occurredAt:   { from: QbOperatorSettlementLine.occurredAt }
list:
  filters: [settlementId, operatorId, month, lineKind, feeKind, late]
  sorts:   [occurredAt, amount]
  defaultSort: { field: occurredAt, asc: false }
  key: [lineId]
permissions: [qb.admin.finance.read]
---*/
SELECT
    l.{{QbOperatorSettlementLine.lineId}} AS lineId,
    l.{{QbOperatorSettlementLine.settlementId}} AS settlementId,
    l.{{QbOperatorSettlementLine.operatorId}} AS operatorId,
    l.{{QbOperatorSettlementLine.month}} AS month,
    l.{{QbOperatorSettlementLine.lineKind}} AS lineKind,
    l.{{QbOperatorSettlementLine.sponsorId}} AS sponsorId,
    l.{{QbOperatorSettlementLine.chargeId}} AS chargeId,
    l.{{QbOperatorSettlementLine.feeKind}} AS feeKind,
    l.{{QbOperatorSettlementLine.period}} AS period,
    l.{{QbOperatorSettlementLine.late}} AS late,
    l.{{QbOperatorSettlementLine.amount}} AS amount,
    l.{{QbOperatorSettlementLine.excluded}} AS excluded,
    l.{{QbOperatorSettlementLine.giftGross}} AS giftGross,
    l.{{QbOperatorSettlementLine.grantSource}} AS grantSource,
    l.{{QbOperatorSettlementLine.inBase}} AS inBase,
    l.{{QbOperatorSettlementLine.recoverGross}} AS recoverGross,
    l.{{QbOperatorSettlementLine.noShare}} AS noShare,
    l.{{QbOperatorSettlementLine.shareRate}} AS shareRate,
    l.{{QbOperatorSettlementLine.recoveryRate}} AS recoveryRate,
    l.{{QbOperatorSettlementLine.occurredAt}} AS occurredAt
FROM {{QbOperatorSettlementLine}} l
WHERE (CAST(:settlementId AS text) IS NULL OR l.{{QbOperatorSettlementLine.settlementId}} = :settlementId)
  AND (CAST(:operatorId AS text) IS NULL OR l.{{QbOperatorSettlementLine.operatorId}} = :operatorId)
  AND (CAST(:month AS text) IS NULL OR l.{{QbOperatorSettlementLine.month}} = :month)
