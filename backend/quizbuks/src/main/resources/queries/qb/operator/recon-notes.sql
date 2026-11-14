/*---
id: qb.operator.recon-notes
description: "F-M14b: the notes the latest daily reconciliation made about the sponsors assigned to the caller's operator now - publications not ended yet charged the operator's least distribution price, and publications of another sponsor's video that bought no exposures; read only, never a task"
entities: [QbReconRun, QbReconItem, QbSponsor, QbSponsorOperator, QbOperatorMember]
datasets:
  QbReconRun: urn:jabiz:dataset:default:QbReconRun
  QbReconItem: urn:jabiz:dataset:operator:QbReconItem
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbSponsorOperator: urn:jabiz:dataset:default:QbSponsorOperator
  QbOperatorMember: urn:jabiz:dataset:operator:QbOperatorMember
results:
  checkCode:     { from: QbReconItem.check }
  sponsorId:     { from: QbReconItem.partyId }
  company:       { from: QbSponsor.company }
  contactName:   { from: QbSponsor.contactName }
  publications:  { kind: { type: numeric, precision: 19, scale: 0 } }
  scheduledTime: { from: QbReconRun.scheduledTime }
list:
  filters: [checkCode]
  sorts:   [company, checkCode, publications]
  defaultSort: { field: company, asc: true }
  key: [checkCode, sponsorId]
permissions: [qb.operator.use]
---*/
-- Only the two notes (ReconChecks.DISTRIBUTION_AT_FLOOR and EXPOSURE_UNPURCHASED) of the latest run, each one row
-- per sponsor with how many publications it counted. The items' dataset keeps them to the sponsors the operator of
-- the session looks after now (D64); the caller's own membership (Q59c-2) keeps a session with no operator to nothing.
-- A run that noted nothing about them still gives one row, with its time and no note (the page says when it looked).
WITH latest AS (
    SELECT r.{{QbReconRun.runId}} AS l_run, r.{{QbReconRun.scheduledTime}} AS l_time
    FROM {{QbReconRun}} r
    ORDER BY r.{{QbReconRun.scheduledTime}} DESC
    LIMIT 1
)
SELECT
    n.n_check     AS checkCode,
    n.n_sponsor   AS sponsorId,
    n.n_company   AS company,
    n.n_contact   AS contactName,
    n.n_count     AS publications,
    latest.l_time AS scheduledTime
FROM latest
LEFT JOIN LATERAL (
    SELECT i.{{QbReconItem.check}} AS n_check, i.{{QbReconItem.partyId}} AS n_sponsor,
           s.{{QbSponsor.company}} AS n_company, s.{{QbSponsor.contactName}} AS n_contact,
           i.{{QbReconItem.actual}} AS n_count
    FROM {{QbReconItem}} i
    JOIN {{QbSponsor}} s ON CAST(s.{{QbSponsor.userId}} AS text) = i.{{QbReconItem.partyId}}
    JOIN {{QbSponsorOperator}} a ON a.{{QbSponsorOperator.userId}} = s.{{QbSponsor.userId}}
    JOIN {{QbOperatorMember}} me ON me.{{QbOperatorMember.removedAt}} IS NULL
      AND me.{{QbOperatorMember.operatorId}} = a.{{QbSponsorOperator.operatorId}}
    WHERE i.{{QbReconItem.runId}} = latest.l_run
      AND i.{{QbReconItem.check}} IN ('DISTRIBUTION_AT_FLOOR', 'EXPOSURE_UNPURCHASED')
) n ON true
