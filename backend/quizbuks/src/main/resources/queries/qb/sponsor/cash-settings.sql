/*---
id: qb.sponsor.cash-settings
description: >-
  Q87a: where the sponsor's top-ups may go and where they go by default, and whether cash may be turned into Kudos
  and back - the switches as QB_TOPUP_START, QB_TOPUP_BANK_START, QB_KUDOS_BUY and QB_KUDOS_SELL read them
entities: [SysParam]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  targets:       { kind: { type: text, maxLength: 20 } }
  defaultTarget: { kind: { type: text, maxLength: 10 } }
  buyEnabled:    { kind: { type: bool } }
  sellEnabled:   { kind: { type: bool } }
permissions: [qb.sponsor.finance.read]
---*/
-- One row, at the server's clock (the dataset without time travel). A switch not yet published reads as before
-- Q87a, as the processes read it: top-ups go to the Kudos wallet only, and no exchange is offered.
SELECT
    coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.topup.targets' THEN p.{{SysParam.value}} END), 'KUDOS')
        AS targets,
    coalesce(max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.topup.default-target' THEN p.{{SysParam.value}} END),
        'KUDOS') AS defaultTarget,
    coalesce(bool_or(p.{{SysParam.paramKey}} = 'qb.kudos.buy.enabled' AND p.{{SysParam.value}} = 'true'), false)
        AS buyEnabled,
    coalesce(bool_or(p.{{SysParam.paramKey}} = 'qb.kudos.sell.enabled' AND p.{{SysParam.value}} = 'true'), false)
        AS sellEnabled
FROM {{SysParam}} p
WHERE p.{{SysParam.paramKey}} IN ('qb.topup.targets', 'qb.topup.default-target', 'qb.kudos.buy.enabled',
    'qb.kudos.sell.enabled')
