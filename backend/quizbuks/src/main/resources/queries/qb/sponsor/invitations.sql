/*---
id: qb.sponsor.invitations
description: The invitation list of one of the sponsor's publications (Q25), with each address's mail and answer; searched by address
entities: [QbInvitation]
datasets:
  QbInvitation: urn:jabiz:dataset:sponsor:QbInvitation
params:
  publicationId: { like: QbInvitation.publicationId, required: true, description: The publication }
  status:        { like: QbInvitation.status, description: Only invitations in this state }
  q:             { kind: { type: text, maxLength: 100 }, description: Only addresses containing this text }
results:
  invitationId: { from: QbInvitation.invitationId }
  email:        { from: QbInvitation.email }
  status:       { from: QbInvitation.status }
  addedAt:      { from: QbInvitation.addedAt }
  sentCount:    { from: QbInvitation.sentCount }
  lastSentAt:   { from: QbInvitation.lastSentAt }
  startedAt:    { from: QbInvitation.startedAt }
  answeredAt:   { from: QbInvitation.answeredAt }
  score:        { from: QbInvitation.score }
  reward:       { from: QbInvitation.reward }
list:
  filters: [status, addedAt, answeredAt]
  sorts:   [addedAt, answeredAt, email, score]
  defaultSort: { field: addedAt, asc: false }
  key: [invitationId]
permissions: [qb.publication.write]
---*/
-- The sponsor's own lists only (the dataset's scope). The text is found as written: strpos takes it literally.
SELECT
    i.{{QbInvitation.invitationId}} AS invitationId,
    i.{{QbInvitation.email}}        AS email,
    i.{{QbInvitation.status}}       AS status,
    i.{{QbInvitation.addedAt}}      AS addedAt,
    i.{{QbInvitation.sentCount}}    AS sentCount,
    i.{{QbInvitation.lastSentAt}}   AS lastSentAt,
    i.{{QbInvitation.startedAt}}    AS startedAt,
    i.{{QbInvitation.answeredAt}}   AS answeredAt,
    i.{{QbInvitation.score}}        AS score,
    i.{{QbInvitation.reward}}       AS reward
FROM {{QbInvitation}} i
WHERE i.{{QbInvitation.publicationId}} = :publicationId
  AND (CAST(:status AS text) IS NULL OR i.{{QbInvitation.status}} = CAST(:status AS text))
  AND (CAST(:q AS text) IS NULL OR strpos(i.{{QbInvitation.email}}, lower(CAST(:q AS text))) > 0)
