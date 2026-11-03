/*---
id: qb.sponsor.topup-settings
description: "The switches of a sponsor's money roads (Q87): what a top-up may become and the default, whether cash is turned into Kudos and back, whether unpaid fees stop them"
entities: [SysParam]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  targets:       { kind: { type: text, maxLength: 20 } }
  defaultTarget: { kind: { type: text, maxLength: 10 } }
  buyEnabled:    { kind: { type: bool } }
  sellEnabled:   { kind: { type: bool } }
  feeBlock:      { kind: { type: bool } }
permissions: [qb.sponsor.finance.read]
---*/
-- One row. The parameters are read at the server's clock (the dataset without time travel), as the processes read
-- them; one not set yet is its declared value here (the processes refuse instead: INVALID_PARAMS).
SELECT
    CAST(coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.topup.targets' THEN p.{{SysParam.value}} END),
        'KUDOS,CASH') AS varchar(20)) AS targets,
    CAST(coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.topup.default-target' THEN p.{{SysParam.value}} END),
        'KUDOS') AS varchar(10)) AS defaultTarget,
    coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.kudos.buy.enabled' THEN p.{{SysParam.value}} END) = 'true',
        true) AS buyEnabled,
    coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.kudos.sell.enabled' THEN p.{{SysParam.value}} END) = 'true',
        false) AS sellEnabled,
    coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.fee.overdue.block-submit' THEN p.{{SysParam.value}} END)
        = 'true', true) AS feeBlock
FROM {{SysParam}} p
WHERE p.{{SysParam.paramKey}} IN ('qb.topup.targets', 'qb.topup.default-target', 'qb.kudos.buy.enabled',
                                  'qb.kudos.sell.enabled', 'qb.fee.overdue.block-submit')
