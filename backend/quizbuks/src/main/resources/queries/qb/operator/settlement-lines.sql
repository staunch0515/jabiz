/*---
id: qb.operator.settlement-lines
description: "The caller operator's settlement lines (F-M12 §6): each fee counted, each voided fee, each part of a payment made with granted credit, by Sponsor and kind; limited to the caller's operator"
entities: [QbOperatorSettlementLine]
datasets:
  QbOperatorSettlementLine: urn:jabiz:dataset:operator:QbOperatorSettlementLine
params:
  settlementId: { like: QbOperatorSettlementLine.settlementId, description: Only this settlement's lines }
  month:        { like: QbOperatorSettlementLine.month, description: Only this month's }
results:
  lineId:       { from: QbOperatorSettlementLine.lineId }
  settlementId: { from: QbOperatorSettlementLine.settlementId }
  month:        { from: QbOperatorSettlementLine.month }
  lineKind:     { from: QbOperatorSettlementLine.lineKind }
  sponsorId:    { from: QbOperatorSettlementLine.sponsorId }
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
  occurredAt:   { from: QbOperatorSettlementLine.occurredAt }
list:
  filters: [settlementId, month, lineKind, feeKind, late]
  sorts:   [occurredAt, amount]
  defaultSort: { field: occurredAt, asc: false }
  key: [lineId]
permissions: [qb.operator.use]
---*/
-- The caller's operator's only: the operator dataset limits the rows to the session's entry scope (D64). A line of a
-- Sponsor reassigned to another operator since stays visible here (it was this operator's when counted).
SELECT
    l.{{QbOperatorSettlementLine.lineId}} AS lineId,
    l.{{QbOperatorSettlementLine.settlementId}} AS settlementId,
    l.{{QbOperatorSettlementLine.month}} AS month,
    l.{{QbOperatorSettlementLine.lineKind}} AS lineKind,
    l.{{QbOperatorSettlementLine.sponsorId}} AS sponsorId,
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
    l.{{QbOperatorSettlementLine.occurredAt}} AS occurredAt
FROM {{QbOperatorSettlementLine}} l
WHERE (CAST(:settlementId AS text) IS NULL OR l.{{QbOperatorSettlementLine.settlementId}} = :settlementId)
  AND (CAST(:month AS text) IS NULL OR l.{{QbOperatorSettlementLine.month}} = :month)
