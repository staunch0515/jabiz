/*---
id: qb.system.benefit-allocations-due
description: "Q89b-1: the open allocations whose publication is back to its draft or ended (the release job's items)"
entities: [QbBenefitAllocation, QbPublication]
datasets:
  QbBenefitAllocation: urn:jabiz:dataset:default:QbBenefitAllocation
  QbPublication: urn:jabiz:dataset:default:QbPublication
results:
  allocationId: { from: QbBenefitAllocation.allocationId }
list:
  sorts: [allocationId]
  defaultSort: { field: allocationId, asc: true }
  key: [allocationId]
permissions: [qb.benefit.system]
---*/
-- Few rows are open (the partial index); each one's publication is read by its key.
SELECT a.{{QbBenefitAllocation.allocationId}} AS allocationId
FROM {{QbBenefitAllocation}} a
JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = a.{{QbBenefitAllocation.publicationId}}
WHERE a.{{QbBenefitAllocation.status}} = 'OPEN'
  AND p.{{QbPublication.status}} IN ('DRAFT', 'ENDED')
