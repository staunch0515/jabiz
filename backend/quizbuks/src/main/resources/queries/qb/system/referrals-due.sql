/*---
id: qb.system.referrals-due
description: Referrals whose referee passed the publication after the claim and before it expired, still to be rewarded, oldest first (the items of the job qb.referral-reward)
entities: [QbReferral, QbAttempt]
datasets:
  QbReferral: urn:jabiz:dataset:default:QbReferral
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
results:
  referralId:    { from: QbReferral.referralId }
  publicationId: { from: QbReferral.publicationId }
  referredAt:    { from: QbReferral.referredAt }
list:
  sorts: [referredAt]
  defaultSort: { field: referredAt, asc: true }
  key: [referralId]
permissions: [qb.referral.system]
---*/
-- Q70 (D-Q70-16): the referrals still waiting (the index on status, expiry) whose referee's one attempt of the
-- publication (qb_attempt_once) passed in time; ReferralRules.earned, the same. A survey's completion is its pass. One
-- the sponsor's wallet could not pay comes again until it is paid or its time is up; the reward reads it again under
-- the referral lock.
SELECT r.{{QbReferral.referralId}} AS referralId, r.{{QbReferral.publicationId}} AS publicationId,
       r.{{QbReferral.referredAt}} AS referredAt
FROM {{QbReferral}} r
JOIN {{QbAttempt}} a ON a.{{QbAttempt.userId}} = r.{{QbReferral.refereeId}}
    AND CAST(a.{{QbAttempt.publicationId}} AS text) = r.{{QbReferral.publicationId}}
WHERE r.{{QbReferral.status}} = 'REFERRED'
  AND a.{{QbAttempt.passed}}
  AND a.{{QbAttempt.submittedAt}} >= r.{{QbReferral.referredAt}}
  AND a.{{QbAttempt.submittedAt}} < r.{{QbReferral.expiresAt}}
