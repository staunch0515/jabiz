/*---
id: finance.fa.roll_forward
description: >-
  The roll-forward of cost and accumulated depreciation over a period (FIN-FA-009), by class and accounts: cost at
  the start, additions (placed in service in the period), disposals and cost at the end; accumulated depreciation at
  the start, the period's depreciation (posted runs dated in it and the disposal months' depreciation), what disposals
  took off, and at the end. The ends equal the register on those days. A voided bill's asset leaves on the void
  date, among the disposals. Depreciation posted after the period's end on an asset disposed of within it is left
  out of what the disposal took off as well, so the asset ends at zero. From the cutover on.
entities: [FinAsset, FinAssetClass, FinDepreciationRun, FinDepreciationLine, FinAssetDisposal, FinBill]
params:
  from: { like: FinAssetDisposal.disposalDate, required: true, description: "the first day of the period" }
  to:   { like: FinAssetDisposal.disposalDate, required: true, description: "the last day of the period" }
results:
  classCode:           { from: FinAsset.classCode }
  costAccount:         { from: FinAsset.costAccount }
  accumulatedAccount:  { from: FinAssetClass.accumulatedAccount }
  costOpening:         { from: FinAsset.cost }
  additions:           { from: FinAsset.cost }
  disposals:           { from: FinAsset.cost }
  costClosing:         { from: FinAsset.cost }
  accumulatedOpening:  { from: FinAsset.accumulated }
  depreciation:        { from: FinAsset.accumulated }
  disposedAccumulated: { from: FinAsset.accumulated }
  accumulatedClosing:  { from: FinAsset.accumulated }
list:
  filters: [classCode, costAccount, accumulatedAccount]
  sorts:   [classCode, costAccount]
  defaultSort: { field: costAccount, asc: true }
  key: [costAccount, classCode]
permissions: [fin.fa.read]
report:
  period: { from: from, to: to }
---*/
WITH runs AS (
    SELECT l.{{FinDepreciationLine.assetId}} AS asset_key,
           SUM(CASE WHEN r.{{FinDepreciationRun.postingDate}} < :from THEN l.{{FinDepreciationLine.amount}} ELSE 0 END) AS before_amt,
           SUM(CASE WHEN r.{{FinDepreciationRun.postingDate}} BETWEEN :from AND :to
                    THEN l.{{FinDepreciationLine.amount}} ELSE 0 END) AS within_amt,
           SUM(CASE WHEN r.{{FinDepreciationRun.postingDate}} > :to THEN l.{{FinDepreciationLine.amount}} ELSE 0 END) AS after_amt
    FROM {{FinDepreciationLine}} l
    JOIN {{FinDepreciationRun}} r ON r.{{FinDepreciationRun.runId}} = l.{{FinDepreciationLine.runId}}
    WHERE r.{{FinDepreciationRun.status}} = 'POSTED'
    GROUP BY l.{{FinDepreciationLine.assetId}}
),
gone AS (
    -- When an asset left: its disposal, or the void of the bill that made it.
    SELECT a.{{FinAsset.assetId}} AS asset_key,
           COALESCE(d.{{FinAssetDisposal.disposalDate}},
                    CASE WHEN a.{{FinAsset.active}} THEN NULL ELSE b.{{FinBill.voidDate}} END) AS gone_on,
           d.{{FinAssetDisposal.monthDepreciation}} AS month_dep, d.{{FinAssetDisposal.accumulated}} AS dep_out
    FROM {{FinAsset}} a
    LEFT JOIN {{FinAssetDisposal}} d ON d.{{FinAssetDisposal.assetId}} = a.{{FinAsset.assetId}}
    LEFT JOIN {{FinBill}} b ON b.{{FinBill.billId}} = a.{{FinAsset.sourceBillId}}
),
assets AS (
    SELECT a.{{FinAsset.classCode}} AS class_key, a.{{FinAsset.costAccount}} AS cost_acct,
           c.{{FinAssetClass.accumulatedAccount}} AS acc_acct, a.{{FinAsset.cost}} AS asset_cost,
           a.{{FinAsset.inServiceDate}} < :from AS held_before,
           CASE WHEN g.gone_on <= :to THEN g.gone_on END AS disposed_on,
           COALESCE(a.{{FinAsset.openingAccumulated}}, 0) + COALESCE(r.before_amt, 0) AS acc_before,
           COALESCE(r.within_amt, 0)
             + CASE WHEN g.gone_on <= :to THEN COALESCE(g.month_dep, 0) ELSE 0 END AS acc_within,
           CASE WHEN g.gone_on <= :to
                THEN COALESCE(g.dep_out, 0) - COALESCE(r.after_amt, 0) ELSE 0 END AS acc_out
    FROM {{FinAsset}} a
    JOIN gone g ON g.asset_key = a.{{FinAsset.assetId}}
    LEFT JOIN {{FinAssetClass}} c ON c.{{FinAssetClass.classCode}} = a.{{FinAsset.classCode}}
    LEFT JOIN runs r ON r.asset_key = a.{{FinAsset.assetId}}
    WHERE a.{{FinAsset.inServiceDate}} <= :to
      -- Inactive but for a void: never an asset.
      AND (a.{{FinAsset.active}} = true OR g.gone_on IS NOT NULL)
      -- Gone before the period: no part of it.
      AND (g.gone_on IS NULL OR g.gone_on >= :from)
),
sums AS (
    SELECT class_key, cost_acct, acc_acct,
           SUM(CASE WHEN held_before THEN asset_cost ELSE 0 END) AS cost_open,
           SUM(CASE WHEN held_before THEN 0 ELSE asset_cost END) AS cost_in,
           SUM(CASE WHEN disposed_on IS NOT NULL THEN asset_cost ELSE 0 END) AS cost_out,
           SUM(CASE WHEN held_before THEN acc_before ELSE 0 END) AS acc_open,
           SUM(acc_within) AS acc_in,
           SUM(acc_out) AS acc_gone
    FROM assets
    GROUP BY class_key, cost_acct, acc_acct
)
SELECT class_key AS classCode, cost_acct AS costAccount, acc_acct AS accumulatedAccount,
       cost_open AS costOpening, cost_in AS additions, cost_out AS disposals,
       cost_open + cost_in - cost_out AS costClosing,
       acc_open AS accumulatedOpening, acc_in AS depreciation, acc_gone AS disposedAccumulated,
       acc_open + acc_in - acc_gone AS accumulatedClosing
FROM sums
