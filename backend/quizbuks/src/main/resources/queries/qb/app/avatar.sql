/*---
id: qb.app.avatar
description: >-
  Q114 (docs/quizbuks/plans/impl/Q114.md): the caller's own avatar (none chosen: the initial) and whether a photo can
  be newly chosen now (qb.avatar.photo.enabled; QB_AVATAR_SET reads the same switch and decides). Always one row.
entities: [QbTakerAvatar, SysParam]
datasets:
  QbTakerAvatar: urn:jabiz:dataset:me:QbTakerAvatar
  SysParam: urn:jabiz:dataset:now:SysParam
results:
  kind:         { from: QbTakerAvatar.kind }
  preset:       { from: QbTakerAvatar.preset }
  photo:        { from: QbTakerAvatar.photo }
  photoEnabled: { kind: { type: bool } }
permissions: [qb.me]
---*/
WITH settings AS (
    SELECT coalesce(bool_or(p.{{SysParam.value}} = 'true'), false) AS photo_enabled
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.avatar.photo.enabled')
SELECT
    a.{{QbTakerAvatar.kind}}   AS kind,
    a.{{QbTakerAvatar.preset}} AS preset,
    a.{{QbTakerAvatar.photo}}  AS photo,
    s.photo_enabled            AS photoEnabled
FROM settings s
LEFT JOIN {{QbTakerAvatar}} a ON true
