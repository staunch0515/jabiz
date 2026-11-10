/*---
id: qb.sponsor.package-state
description: >-
  F-C4: what the import of a sponsor package needs to know of the account: whether it submitted a publication (then only
  the profile part is imported) or a knowledge map (then the package's maps are not imported, its publications are
  bound by node key), how many maps it has and may have, and above how many bytes an export asks first
entities: [QbPublication, QbTree, QbTreeVersion, SysParam]
datasets:
  QbPublication: urn:jabiz:dataset:sponsor:QbPublication
  QbTree: urn:jabiz:dataset:sponsor:QbTree
  QbTreeVersion: urn:jabiz:dataset:default:QbTreeVersion
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  submittedPublication: { kind: { type: bool } }
  submittedTree:        { kind: { type: bool } }
  activeTrees:          { kind: { type: numeric, precision: 9, scale: 0 } }
  maxTrees:             { kind: { type: numeric, precision: 9, scale: 0 } }
  confirmBytes:         { kind: { type: numeric, precision: 15, scale: 0 } }
permissions: [qb.sponsor.me]
---*/
-- One row. The caller's own publications and maps only (the sponsor datasets are limited to the caller); the versions
-- are read through the caller's maps. The rules are the processes' (QB_PACKAGE_IMPORT_START refuses a whole package
-- for an account that submitted a publication; QB_TREE_CREATE counts the maps): this is for the page. A parameter not
-- set (before setup) or not a whole number gives its declared value.
SELECT
    EXISTS (SELECT 1 FROM {{QbPublication}} p
            WHERE p.{{QbPublication.status}} <> 'DRAFT' OR p.{{QbPublication.submittedAt}} IS NOT NULL)
        AS submittedPublication,
    EXISTS (SELECT 1 FROM {{QbTree}} t
            WHERE t.{{QbTree.currentVersionId}} IS NOT NULL
               OR EXISTS (SELECT 1 FROM {{QbTreeVersion}} v
                          WHERE v.{{QbTreeVersion.treeId}} = t.{{QbTree.treeId}}
                            AND v.{{QbTreeVersion.submittedAt}} IS NOT NULL))
        AS submittedTree,
    (SELECT count(*) FROM {{QbTree}} t WHERE t.{{QbTree.status}} = 'ACTIVE') AS activeTrees,
    coalesce((SELECT CAST(max(v.{{SysParam.value}}) AS numeric) FROM {{SysParam}} v
              WHERE v.{{SysParam.paramKey}} = 'qb.tree.sponsor.max-trees' AND v.{{SysParam.value}} ~ '^[0-9]{1,9}$'),
             1) AS maxTrees,
    coalesce((SELECT CAST(max(v.{{SysParam.value}}) AS numeric) FROM {{SysParam}} v
              WHERE v.{{SysParam.paramKey}} = 'qb.package.confirm-bytes' AND v.{{SysParam.value}} ~ '^[0-9]{1,15}$'),
             104857600) AS confirmBytes
