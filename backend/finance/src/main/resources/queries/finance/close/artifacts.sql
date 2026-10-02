/*---
id: finance.close.artifacts
description: >-
  The close artifacts (FIN-PC-005, PC-006): every close of a period with who closed it and when, its totals and
  hashes and the issued trial balance; a period closed again after a reopening has a later artifact that supersedes
  the earlier, which stays as it was.
entities: [FinCloseArtifact]
params:
  periodKey: { like: FinCloseArtifact.periodKey, description: "one period only; all when not given" }
results:
  artifactId:       { kind: { type: text, maxLength: 40 } }
  periodKey:        { from: FinCloseArtifact.periodKey }
  seq:              { from: FinCloseArtifact.seq }
  closedBy:         { from: FinCloseArtifact.closedBy }
  closedAt:         { from: FinCloseArtifact.closedAt }
  totalDebit:       { from: FinCloseArtifact.totalDebit }
  totalCredit:      { from: FinCloseArtifact.totalCredit }
  trialBalanceHash: { from: FinCloseArtifact.trialBalanceHash }
  contentHash:      { from: FinCloseArtifact.contentHash }
  reportRunId:      { from: FinCloseArtifact.reportRunId }
  supersedes:       { kind: { type: text, maxLength: 40 } }
  supersededBy:     { kind: { type: text, maxLength: 40 } }
list:
  filters: [periodKey]
  sorts:   [periodKey, closedAt]
  defaultSort: { field: closedAt, asc: false }
  key: [artifactId]
permissions: [fin.period.read]
---*/
SELECT CAST(a.{{FinCloseArtifact.artifactId}} AS text) AS artifactId, a.{{FinCloseArtifact.periodKey}} AS periodKey,
       a.{{FinCloseArtifact.seq}} AS seq, a.{{FinCloseArtifact.closedBy}} AS closedBy,
       a.{{FinCloseArtifact.closedAt}} AS closedAt, a.{{FinCloseArtifact.totalDebit}} AS totalDebit,
       a.{{FinCloseArtifact.totalCredit}} AS totalCredit, a.{{FinCloseArtifact.trialBalanceHash}} AS trialBalanceHash,
       a.{{FinCloseArtifact.contentHash}} AS contentHash, a.{{FinCloseArtifact.reportRunId}} AS reportRunId,
       CAST(a.{{FinCloseArtifact.supersedesId}} AS text) AS supersedes,
       (SELECT CAST(n.{{FinCloseArtifact.artifactId}} AS text) FROM {{FinCloseArtifact}} n
        WHERE n.{{FinCloseArtifact.supersedesId}} = a.{{FinCloseArtifact.artifactId}}) AS supersededBy
FROM {{FinCloseArtifact}} a
WHERE (CAST(:periodKey AS text) IS NULL OR a.{{FinCloseArtifact.periodKey}} = :periodKey)
