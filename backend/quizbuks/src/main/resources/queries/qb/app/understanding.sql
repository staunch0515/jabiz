/*---
id: qb.app.understanding
description: >-
  The understanding certifications (Q86): whether the cards show, which the caller holds and until when, and the
  publication of each one's current round (none: no round open now); the platform's tree handle (D-Q86-4)
entities: [SysParam, QbCertificationRound, QbCertificationRecord, QbPublicationCard, QbAttempt]
datasets:
  SysParam: urn:jabiz:dataset:now:SysParam
  QbCertificationRound: urn:jabiz:dataset:default:QbCertificationRound
  QbCertificationRecord: urn:jabiz:dataset:me:QbCertificationRecord
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbAttempt: urn:jabiz:dataset:taker:QbAttempt
params:
  at: { kind: { type: temporal, role: EVENT_TIME }, required: true, description: The time the cards are for (the client's now) }
results:
  certification:      { kind: { type: text, maxLength: 40 } }
  enabled:            { kind: { type: bool } }
  heldUntil:          { kind: { type: temporal, role: EVENT_TIME } }
  roundPublicationId: { kind: { type: text, maxLength: 36 } }
  platformHandle:     { kind: { type: text, maxLength: 60 } }
list:
  key: [certification]
  sorts: [certification]
  defaultSort: { field: certification, asc: true }
permissions: [qb.account.read]
---*/
-- One row per understanding certification (the catalog as Certifications writes it, CertificationTemplatesTest). The
-- holding is CertificationHolding's rule over the caller's records and their passes not recorded yet (as qb.app.feed);
-- the round is the newest open one whose publication is live now (UUIDv7: the greatest id; one forgotten open does
-- not keep the card on last month's questions). The switch and the platform's handle are parameters.
SELECT
    cat.cert AS certification,
    coalesce((SELECT CAST(sp.{{SysParam.value}} AS text) = 'true' FROM {{SysParam}} sp
              WHERE sp.{{SysParam.paramKey}} = 'qb.understanding.enabled'), true) AS enabled,
    (SELECT max(e.until)
     FROM (SELECT rec.{{QbCertificationRecord.certificationVersion}} AS ver, rec.{{QbCertificationRecord.at}} AS at,
                  rec.{{QbCertificationRecord.validUntil}} AS until
           FROM {{QbCertificationRecord}} rec
           WHERE rec.{{QbCertificationRecord.certification}} = cat.cert
             AND rec.{{QbCertificationRecord.kind}} IN ('PASSED', 'GRANTED')
           UNION ALL
           SELECT r2.{{QbCertificationRound.certificationVersion}}, a.{{QbAttempt.submittedAt}},
                  a.{{QbAttempt.submittedAt}} + make_interval(secs => cat.secs)
           FROM {{QbCertificationRound}} r2
           JOIN {{QbAttempt}} a ON CAST(a.{{QbAttempt.publicationId}} AS text) = r2.{{QbCertificationRound.publicationId}}
           WHERE r2.{{QbCertificationRound.certification}} = cat.cert
             AND a.{{QbAttempt.passed}} AND a.{{QbAttempt.submittedAt}} IS NOT NULL
             AND (r2.{{QbCertificationRound.closedAt}} IS NULL
                  OR a.{{QbAttempt.submittedAt}} < r2.{{QbCertificationRound.closedAt}})) e
     WHERE e.ver = cat.ver AND e.until > CAST(:at AS timestamptz)
       AND e.at > coalesce((SELECT max(rv.{{QbCertificationRecord.at}}) FROM {{QbCertificationRecord}} rv
                            WHERE rv.{{QbCertificationRecord.certification}} = cat.cert
                              AND rv.{{QbCertificationRecord.kind}} = 'REVOKED'), '-infinity')) AS heldUntil,
    (SELECT max(cr.{{QbCertificationRound.publicationId}})
     FROM {{QbCertificationRound}} cr
     JOIN {{QbPublicationCard}} rp ON CAST(rp.{{QbPublicationCard.publicationId}} AS text) = cr.{{QbCertificationRound.publicationId}}
     WHERE cr.{{QbCertificationRound.certification}} = cat.cert AND cr.{{QbCertificationRound.closedAt}} IS NULL
       AND rp.{{QbPublicationCard.status}} = 'LIVE' AND rp.{{QbPublicationCard.startAt}} <= CAST(:at AS timestamptz)
       AND rp.{{QbPublicationCard.endAt}} > CAST(:at AS timestamptz)) AS roundPublicationId,
    (SELECT CAST(sp.{{SysParam.value}} AS text) FROM {{SysParam}} sp
     WHERE sp.{{SysParam.paramKey}} = 'qb.tree.platform-handle') AS platformHandle
FROM (VALUES ('SPONSOR_APPLY', 1, 31536000), ('REFERRER', 1, 31536000), ('TERMS_APP_UNDERSTOOD', 1, 31536000), ('CONTEST_RULES_UNDERSTOOD', 1, 31536000), ('TERMS_SPONSOR_UNDERSTOOD', 1, 31536000)) AS cat(cert, ver, secs)
WHERE cat.cert IN ('TERMS_APP_UNDERSTOOD', 'CONTEST_RULES_UNDERSTOOD', 'TERMS_SPONSOR_UNDERSTOOD')
