/*---
id: qb.system.tree-certificates-due
description: The people whose lit records have a certificate to be written (the items of the daily job qb.tree-certificate-retry, F-S5a) - a lit record that froze the names of a certificate, was not revoked and has no certificate yet
entities: [QbTreeLit, QbTreeLitRevocation, QbCertificate, Erasure]
datasets:
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
  QbCertificate: urn:jabiz:dataset:default:QbCertificate
  Erasure: urn:jabiz:dataset:platform:Erasure
results:
  subjectId: { from: QbTreeLit.subjectId }
list:
  sorts: [subjectId]
  defaultSort: { field: subjectId, asc: true }
  key: [subjectId]
permissions: [qb.certificate.system]
---*/
-- The same rule as certification.NodeCertificates#due, which the item runs again under the person's certificate lock.
-- An erased account (platform D62) has none: the lit records stay, holding ids only.
-- A person is one item whatever kinds of lit record they have (a Taker's and a Sponsor's).
SELECT DISTINCT l.{{QbTreeLit.subjectId}} AS subjectId
FROM {{QbTreeLit}} l
WHERE l.{{QbTreeLit.certificateNames}} IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                  WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text))
  AND NOT EXISTS (SELECT 1 FROM {{QbCertificate}} c
                  WHERE c.{{QbCertificate.litId}} = CAST(l.{{QbTreeLit.litId}} AS text))
  AND NOT EXISTS (SELECT 1 FROM {{Erasure}} e WHERE CAST(e.{{Erasure.userId}} AS text) = l.{{QbTreeLit.subjectId}})
UNION
-- A node's certificate of an erased account (platform ACCOUNT_ERASE takes no lock of ours, so one can be written after it):
-- the item removes it.
SELECT DISTINCT c.{{QbCertificate.userId}}
FROM {{QbCertificate}} c
WHERE c.{{QbCertificate.source}} = 'NODE'
  AND EXISTS (SELECT 1 FROM {{Erasure}} e WHERE CAST(e.{{Erasure.userId}} AS text) = c.{{QbCertificate.userId}})
