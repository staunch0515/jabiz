/*---
id: qb.sponsor.video-use-history
description: >-
  F-S14: the history of one of the Sponsor's uses (the user's side): every version, the act that wrote it, when, and what it said
entities: [QbVideoUseHistory, QbVideoUse]
datasets:
  QbVideoUseHistory: urn:jabiz:dataset:sponsor:QbVideoUseHistory
  QbVideoUse: urn:jabiz:dataset:sponsor:QbVideoUse
params:
  videoUseId: { kind: { type: text, maxLength: 36 }, required: true, description: The use }
results:
  versionNo:     { from: QbVideoUseHistory.versionNo }
  processName:   { from: QbVideoUseHistory.processName }
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
permissions: [qb.stats.read]
---*/
-- The user's own versions (both datasets hold the caller's as the user); who acted is the side, not an id.
-- The act is the process that wrote the version (QB_VIDEO_USE_SAVE, _SUBMIT, _CONFIRM, _REJECT; the history is the
-- append-only versions themselves, docs/quizbuks/plans/impl/F-S14.md).
SELECT h.{{QbVideoUseHistory.versionNo}} AS versionNo,
       h.{{QbVideoUseHistory.processName}} AS processName,
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
