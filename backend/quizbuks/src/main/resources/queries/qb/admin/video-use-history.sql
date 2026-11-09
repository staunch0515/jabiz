/*---
id: qb.admin.video-use-history
description: >-
  F-S14: the history of a use, read only, as headquarters and the users' operators read it: every version, who wrote it, the act and when
entities: [QbVideoUseHistory, QbVideoUse]
datasets:
  QbVideoUseHistory: urn:jabiz:dataset:default:QbVideoUseHistory
  QbVideoUse: urn:jabiz:dataset:default:QbVideoUse
params:
  videoUseId: { kind: { type: text, maxLength: 36 }, required: true, description: The use }
results:
  versionNo:     { from: QbVideoUseHistory.versionNo }
  processName:   { from: QbVideoUseHistory.processName }
  actorId:       { from: QbVideoUseHistory.actorId }
  recordedAt:    { from: QbVideoUseHistory.recordedAt }
  status:        { from: QbVideoUseHistory.status }
  pricing:       { from: QbVideoUseHistory.pricing }
  price:         { from: QbVideoUseHistory.price }
  periodFrom:    { from: QbVideoUseHistory.periodFrom }
  periodTo:      { from: QbVideoUseHistory.periodTo }
  rejectReason:  { from: QbVideoUseHistory.rejectReason }
  revision:      { from: QbVideoUseHistory.revision }
list:
  sorts: [versionNo]
  defaultSort: { field: versionNo, asc: true }
  key: [versionNo]
permissions: [qb.admin.video-use.read]
---*/
-- Headquarters reads every use's, an operator its own sponsors' (the users') only: the entry scope of both datasets.
-- The act is the process that wrote the version (QB_VIDEO_USE_SAVE, _SUBMIT, _CONFIRM, _REJECT; the history is the
-- append-only versions themselves, docs/quizbuks/plans/impl/F-S14.md).
SELECT h.{{QbVideoUseHistory.versionNo}} AS versionNo,
       h.{{QbVideoUseHistory.processName}} AS processName,
       h.{{QbVideoUseHistory.actorId}} AS actorId,
       h.{{QbVideoUseHistory.recordedAt}} AS recordedAt,
       h.{{QbVideoUseHistory.status}} AS status,
       h.{{QbVideoUseHistory.pricing}} AS pricing,
       h.{{QbVideoUseHistory.price}} AS price,
       h.{{QbVideoUseHistory.periodFrom}} AS periodFrom,
       h.{{QbVideoUseHistory.periodTo}} AS periodTo,
       h.{{QbVideoUseHistory.rejectReason}} AS rejectReason,
       h.{{QbVideoUseHistory.revision}} AS revision
FROM {{QbVideoUseHistory}} h
WHERE h.{{QbVideoUseHistory.videoUseId}} = CAST(:videoUseId AS text)
  AND EXISTS (SELECT 1 FROM {{QbVideoUse}} u
              WHERE CAST(u.{{QbVideoUse.videoUseId}} AS text) = h.{{QbVideoUseHistory.videoUseId}})
