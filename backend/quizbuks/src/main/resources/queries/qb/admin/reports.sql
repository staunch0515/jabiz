/*---
id: qb.admin.reports
description: >-
  Takers' reports (Q49a), one row per reported publication: its name, state and sponsor, how many reports are open and
  in all, by reason, and the latest; the reviewers' list to dismiss or act on. An operator's session sees its own
  sponsors' only
entities: [QbReport, QbPublication, QbSponsor]
datasets:
  QbReport: urn:jabiz:dataset:default:QbReport
  QbPublication: urn:jabiz:dataset:default:QbPublication
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
params:
  open: { kind: { type: bool }, description: "Only publications with open reports (true) or with none open (false)" }
results:
  publicationId:   { from: QbReport.publicationId }
  name:            { from: QbPublication.name }
  status:          { from: QbPublication.status }
  publisherKind:   { from: QbReport.publisherKind }
  publisherId:     { from: QbReport.publisherId }
  company:         { from: QbSponsor.company }
  openCount:       { kind: { type: numeric, precision: 9, scale: 0 } }
  totalCount:      { kind: { type: numeric, precision: 9, scale: 0 } }
  misleading:      { kind: { type: numeric, precision: 9, scale: 0 } }
  inappropriate:   { kind: { type: numeric, precision: 9, scale: 0 } }
  rewardNotGiven:  { kind: { type: numeric, precision: 9, scale: 0 } }
  spam:            { kind: { type: numeric, precision: 9, scale: 0 } }
  other:           { kind: { type: numeric, precision: 9, scale: 0 } }
  lastReportedAt:  { from: QbReport.createdAt }
list:
  filters: [status, company, name]
  sorts:   [lastReportedAt, openCount, totalCount, name]
  defaultSort: { field: lastReportedAt, asc: false }
  key: [publicationId]
permissions: [qb.review.publication]
---*/
-- Every dataset is limited to the operator's sponsors for an operator's session (Q59c-2). Closing the reports is
-- QB_REPORT_RESOLVE (headquarters) or QB_OPERATOR_REPORT_RESOLVE; pausing or ending the quiz the existing processes.
SELECT
    r.publication_id         AS publicationId,
    p.{{QbPublication.name}}   AS name,
    p.{{QbPublication.status}} AS status,
    r.publisher_kind         AS publisherKind,
    r.publisher_id           AS publisherId,
    s.{{QbSponsor.company}}    AS company,
    r.open_count             AS openCount,
    r.total_count            AS totalCount,
    r.misleading             AS misleading,
    r.inappropriate          AS inappropriate,
    r.reward_not_given       AS rewardNotGiven,
    r.spam                   AS spam,
    r.other                  AS other,
    r.last_reported_at       AS lastReportedAt
FROM (
    SELECT
        x.{{QbReport.publicationId}} AS publication_id,
        min(x.{{QbReport.publisherKind}}) AS publisher_kind,
        min(x.{{QbReport.publisherId}})   AS publisher_id,
        count(*) FILTER (WHERE x.{{QbReport.status}} = 'OPEN')                AS open_count,
        count(*)                                                              AS total_count,
        count(*) FILTER (WHERE x.{{QbReport.reason}} = 'MISLEADING')          AS misleading,
        count(*) FILTER (WHERE x.{{QbReport.reason}} = 'INAPPROPRIATE')       AS inappropriate,
        count(*) FILTER (WHERE x.{{QbReport.reason}} = 'REWARD_NOT_GIVEN')    AS reward_not_given,
        count(*) FILTER (WHERE x.{{QbReport.reason}} = 'SPAM')                AS spam,
        count(*) FILTER (WHERE x.{{QbReport.reason}} = 'OTHER')               AS other,
        max(x.{{QbReport.createdAt}})                                         AS last_reported_at
    FROM {{QbReport}} x
    GROUP BY x.{{QbReport.publicationId}}
) r
LEFT JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = r.publication_id
LEFT JOIN {{QbSponsor}} s ON r.publisher_kind = 'SPONSOR' AND s.{{QbSponsor.userId}} = r.publisher_id
WHERE (CAST(:open AS boolean) IS NULL OR (r.open_count > 0) = CAST(:open AS boolean))
