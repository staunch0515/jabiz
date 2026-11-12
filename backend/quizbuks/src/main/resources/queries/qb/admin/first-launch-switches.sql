/*---
id: qb.admin.first-launch-switches
description: >-
  The first launch's money switches in force (F-F10b): each parameter the registry setup.FirstLaunchSwitches lists as
  active, with the value the first launch proposes (null where the declared value stands: an amount), the stored value
  in force and whether the parameter exists yet. The finance page's card sets it against the open proposals; changes go
  through the controlled change (four eyes), never from here
entities: [SysParam]
results:
  paramKey:    { kind: { type: text, maxLength: 200 } }
  grp:         { kind: { type: text, maxLength: 30 } }
  expected:    { kind: { type: text, maxLength: 200 } }
  paramId:     { from: SysParam.paramId }
  value:       { from: SysParam.value }
  paramExists: { kind: { type: bool } }
  seq:         { kind: { type: numeric, precision: 3, scale: 0 } }
list:
  sorts:   [seq]
  defaultSort: { field: seq, asc: true }
  key: [paramKey]
permissions: [qb.admin.finance.read]
---*/
-- The VALUES are the registry's active rows, in its order, then its waiting rows whose key already exists
-- (KEY_BEFORE_MEANING: off until their feature is there) (key, group, first launch value as the parameter's text, seq):
-- FirstLaunchSwitchesTemplateTest compares them row by row, so the two cannot drift. The other rows that wait for a
-- feature, and the ones set up by hand, are not here (the card lists them from the registry's names). paramExists is false until the
-- setup's proposal of a controlled parameter is published; expected is null for an amount (the declared value stands).
WITH wanted (k, grp, expected, seq) AS (
    VALUES
        ('qb.fee-credit.grant.enabled', 'grant', 'true', 1),
        ('qb.fee-credit.grant.basic', 'grant', CAST(NULL AS text), 2),
        ('qb.fee-credit.grant.certified', 'grant', CAST(NULL AS text), 3),
        ('qb.fee-credit.valid-days', 'grant', CAST(NULL AS text), 4),
        ('qb.help.enabled', 'help', 'true', 5),
        ('qb.help.user-daily', 'help', CAST(NULL AS text), 6),
        ('qb.help.global-monthly-tokens', 'help', CAST(NULL AS text), 7),
        ('qb.ai.enabled', 'ai', 'true', 8),
        ('qb.fee.monthly', 'fees', '0', 9),
        ('qb.creator-fee-rate', 'fees', '0', 10),
        ('qb.transfer.fee-rate', 'fees', '0', 11),
        ('qb.topup.targets', 'topup', 'CASH', 12),
        ('qb.topup.default-target', 'topup', 'CASH', 13),
        ('qb.kudos.buy.enabled', 'kudos', 'false', 14),
        ('qb.kudos.sell.enabled', 'kudos', 'false', 15),
        ('qb.transfer.enabled', 'kudos', 'false', 16),
        ('qb.withdrawal.bank.enabled', 'kudos', 'false', 17),
        ('qb.reward.kudos.enabled', 'kudos', 'false', 18),
        ('qb.reward.default-methods', 'kudos', '', 19),
        ('qb.play.entry-fee.enabled', 'kudos', 'false', 20),
        ('qb.audience.billing.enabled', 'audience', 'false', 21),
        ('qb.ai.site-profile.enabled', 'aifee', 'false', 22))
SELECT
    CAST(w.k AS varchar(200))        AS paramKey,
    CAST(w.grp AS varchar(30))       AS grp,
    CAST(w.expected AS varchar(200)) AS expected,
    p.{{SysParam.paramId}}           AS paramId,
    p.{{SysParam.value}}             AS value,
    p.{{SysParam.paramKey}} IS NOT NULL AS paramExists,
    w.seq AS seq
FROM wanted w
LEFT JOIN {{SysParam}} p ON p.{{SysParam.paramKey}} = w.k
