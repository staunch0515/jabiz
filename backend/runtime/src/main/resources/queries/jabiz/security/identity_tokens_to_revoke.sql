/*---
id: jabiz.identity_tokens_to_revoke
description: >-
  The kept provider tokens whose link is gone (decision D67 item 5; docs/design/10-security.md section 12) and that
  are due for an attempt: the job jabiz.identity-token-revocation runs SEC_IDENTITY_TOKEN_REVOKE for each, oldest first.
  Tokens tried the most times allowed are left for an administrator.
entities: [SecIdentityToken, SecUserIdentity]
params:
  dueBy:       { like: SecIdentityToken.nextAttemptAt, required: true, description: Attempts due at or before this time }
  maxAttempts: { kind: { type: numeric, precision: 9, scale: 0 }, required: true, description: Attempts allowed }
results:
  identityId: { from: SecIdentityToken.identityId }
  updatedAt:  { from: SecIdentityToken.updatedAt }
list:
  sorts: [updatedAt]
  defaultSort: { field: updatedAt, asc: true }
  key: [identityId]
permissions: [security.identity-token.revoke]
---*/
SELECT t.{{SecIdentityToken.identityId}} AS identityId, t.{{SecIdentityToken.updatedAt}} AS updatedAt
FROM {{SecIdentityToken}} t
WHERE t.{{SecIdentityToken.revokeAttempts}} < :maxAttempts
  AND (t.{{SecIdentityToken.nextAttemptAt}} IS NULL OR t.{{SecIdentityToken.nextAttemptAt}} <= :dueBy)
  AND NOT EXISTS (SELECT 1 FROM {{SecUserIdentity}} i
                  WHERE i.{{SecUserIdentity.userIdentityId}} = t.{{SecIdentityToken.identityId}})
