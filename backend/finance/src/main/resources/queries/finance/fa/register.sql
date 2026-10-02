/*---
id: finance.fa.register
description: >-
  The fixed asset register on a day (FIN-FA-009): every asset in service by then and not disposed of, with its cost,
  what it had accumulated at the end of the day — what it brought over at the cutover and the posted depreciation runs
  dated by then — and its net book value. Its cost adds up to the cost accounts and its accumulated depreciation to
  the accumulated depreciation accounts on that day; the asset of a bill voided later is held until the void. From
  the cutover on, the accumulated depreciation of a day before it being what was brought over.
entities: [FinAsset, FinAssetClass, FinDepreciationRun, FinDepreciationLine, FinAssetDisposal, FinBill]
params:
  asOf:      { like: FinAssetDisposal.disposalDate, required: true, description: "the day of the register, at its end" }
  classCode: { like: FinAsset.classCode, description: "one class only" }
results:
  assetNo:            { from: FinAsset.assetNo }
  description:        { from: FinAsset.description }
  classCode:          { from: FinAsset.classCode }
  costAccount:        { from: FinAsset.costAccount }
  accumulatedAccount: { from: FinAssetClass.accumulatedAccount }
  inServiceDate:      { from: FinAsset.inServiceDate }
  method:             { from: FinAsset.method }
  lifeMonths:         { from: FinAsset.lifeMonths }
  department:         { from: FinAsset.department }
  location:           { from: FinAsset.location }
  custodian:          { from: FinAsset.custodian }
  cost:               { from: FinAsset.cost }
  accumulated:        { from: FinAsset.accumulated }
  netBookValue:       { from: FinAsset.cost }
  status:             { kind: { type: text, maxLength: 20 } }
list:
  filters: [assetNo, classCode, costAccount, accumulatedAccount, status, department, location]
  sorts:   [assetNo, classCode, inServiceDate, cost, netBookValue]
  defaultSort: { field: assetNo, asc: true }
  key: [assetNo]
permissions: [fin.fa.read]
report:
  period: { to: asOf }
---*/
WITH taken AS (
    SELECT l.{{FinDepreciationLine.assetId}} AS asset_key, SUM(l.{{FinDepreciationLine.amount}}) AS run_total
    FROM {{FinDepreciationLine}} l
    JOIN {{FinDepreciationRun}} r ON r.{{FinDepreciationRun.runId}} = l.{{FinDepreciationLine.runId}}
    WHERE r.{{FinDepreciationRun.status}} = 'POSTED' AND r.{{FinDepreciationRun.postingDate}} <= :asOf
    GROUP BY l.{{FinDepreciationLine.assetId}}
),
held AS (
    SELECT a.{{FinAsset.assetNo}} AS item_no, a.{{FinAsset.description}} AS asset_text,
           a.{{FinAsset.classCode}} AS class_key, a.{{FinAsset.costAccount}} AS cost_acct,
           c.{{FinAssetClass.accumulatedAccount}} AS acc_acct, a.{{FinAsset.inServiceDate}} AS placed,
           a.{{FinAsset.method}} AS how, a.{{FinAsset.lifeMonths}} AS life, a.{{FinAsset.department}} AS dept,
           a.{{FinAsset.location}} AS place, a.{{FinAsset.custodian}} AS keeper, a.{{FinAsset.cost}} AS asset_cost,
           COALESCE(a.{{FinAsset.salvage}}, 0) AS salvage_value,
           COALESCE(a.{{FinAsset.openingAccumulated}}, 0) + COALESCE(t.run_total, 0) AS acc_total
    FROM {{FinAsset}} a
    LEFT JOIN {{FinAssetClass}} c ON c.{{FinAssetClass.classCode}} = a.{{FinAsset.classCode}}
    LEFT JOIN taken t ON t.asset_key = a.{{FinAsset.assetId}}
    LEFT JOIN {{FinAssetDisposal}} d ON d.{{FinAssetDisposal.assetId}} = a.{{FinAsset.assetId}}
    LEFT JOIN {{FinBill}} b ON b.{{FinBill.billId}} = a.{{FinAsset.sourceBillId}}
    WHERE (a.{{FinAsset.active}} = true OR b.{{FinBill.voidDate}} > :asOf)
      AND a.{{FinAsset.inServiceDate}} <= :asOf
      AND (d.{{FinAssetDisposal.disposalDate}} IS NULL OR d.{{FinAssetDisposal.disposalDate}} > :asOf)
      AND (CAST(:classCode AS varchar) IS NULL OR a.{{FinAsset.classCode}} = :classCode)
)
SELECT item_no AS assetNo, asset_text AS description, class_key AS classCode, cost_acct AS costAccount,
       acc_acct AS accumulatedAccount, placed AS inServiceDate, how AS method, life AS lifeMonths,
       dept AS department, place AS location, keeper AS custodian, asset_cost AS cost, acc_total AS accumulated,
       asset_cost - acc_total AS netBookValue,
       CASE WHEN class_key IS NULL THEN 'UNCLASSIFIED'
            WHEN acc_total >= asset_cost - salvage_value THEN 'FULLY_DEPRECIATED'
            ELSE 'IN_SERVICE' END AS status
FROM held
