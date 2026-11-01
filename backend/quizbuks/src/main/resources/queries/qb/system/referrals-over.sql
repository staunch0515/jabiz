/*---
id: qb.system.referrals-over
description: Referrals still waiting whose time is up or whose referee completed the publication without passing in time, oldest first (the items of the job qb.referral-expire)
entities: [QbReferral, QbAttempt]
datasets:
  QbReferral: urn:jabiz:dataset:default:QbReferral
  QbAttempt: urn:jabiz:dataset:default:QbAttempt
params:
  at: { like: QbReferral.expiresAt, required: true, description: The scheduled time }
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
-- Q70 (D-Q70-17): ReferralRules.over, the same: not earned (qb.system.referrals-due), and either expired or the
-- referee's attempt is completed. The expiry reads it again under the referral lock.
SELECT r.{{QbReferral.referralId}} AS referralId, r.{{QbReferral.publicationId}} AS publicationId,
       r.{{QbReferral.referredAt}} AS referredAt
FROM {{QbReferral}} r
LEFT JOIN {{QbAttempt}} a ON a.{{QbAttempt.userId}} = r.{{QbReferral.refereeId}}
    AND CAST(a.{{QbAttempt.publicationId}} AS text) = r.{{QbReferral.publicationId}}
WHERE r.{{QbReferral.status}} = 'REFERRED'
  AND NOT (a.{{QbAttempt.submittedAt}} IS NOT NULL AND a.{{QbAttempt.passed}}
           AND a.{{QbAttempt.submittedAt}} >= r.{{QbReferral.referredAt}}
           AND a.{{QbAttempt.submittedAt}} < r.{{QbReferral.expiresAt}})
  AND (r.{{QbReferral.expiresAt}} <= :at OR a.{{QbAttempt.submittedAt}} IS NOT NULL)
