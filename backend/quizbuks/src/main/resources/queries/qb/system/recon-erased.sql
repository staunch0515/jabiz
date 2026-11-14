/*---
id: qb.system.recon-erased
description: Whether the platform fee accounts of erased sponsors were wound up (F-M15 b §9) - the granted credit left after a wind-up done, wind-ups waiting too long, fees still owed after one (a note), and erased sponsors who had the account with no wind-up registered (the fifth template of the daily fee-account reconciliation)
entities: [QbSponsorClosure, QbFeeCreditGrant, QbFeeInvoice, QbWallet, QbPrepayment, QbFeeCharge, QbTopUp, QbFeeRequest, Erasure]
datasets:
  QbSponsorClosure: urn:jabiz:dataset:default:QbSponsorClosure
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbTopUp: urn:jabiz:dataset:default:QbTopUp
  QbFeeRequest: urn:jabiz:dataset:default:QbFeeRequest
  Erasure: urn:jabiz:dataset:platform:Erasure
params:
  at: { like: QbSponsorClosure.erasedAt, description: "The run's time (none: the two checks by days are not made)" }
  alertDays: { kind: { type: numeric, precision: 2, scale: 0 }, description: "qb.erasure.close.alert-days (none, or not a whole number from 1 to 60: reported at once, as the wind-up's own alert)" }
results:
  checkCode: { kind: { type: text, maxLength: 30 } }
  sponsorId: { kind: { type: text, maxLength: 128 } }
  reference: { kind: { type: text, maxLength: 128 } }
  expected:  { kind: { type: monetary, currency: JPY, scale: 0 } }
  actual:    { kind: { type: monetary, currency: JPY, scale: 0 } }
list:
  sorts: [checkCode, sponsorId]
  defaultSort: { field: checkCode, asc: true }
  key: [checkCode, sponsorId, reference]
permissions: [qb.wallet.system]
---*/
-- One statement, one snapshot.
-- ERASED_GIFT_LEFT (a difference): a sponsor wound up (CLOSED) whose granted credit still has something left in a lot
--   that had not expired when the wind-up was done (the wind-up voids those; one expired before is the expiry job's):
--   expected 0, actual what is left. A voided invoice that gives back to such a lot voids it again at once.
-- ERASED_CLOSE_OPEN (attention, finance): a wind-up still WAITING qb.erasure.close.alert-days or more after the erasure
--   (one closure a sponsor: the sponsor names it): expected 0, actual the whole days waited (an item is what differs).
-- ERASED_FEE_OWED (a note): a sponsor wound up whose invoices still owe (total - credit applied - paid), plus the
--   postpaid fees charged since and not invoiced yet (Fable's re-review; the condition of
--   qb.system.money-close-uninvoiced): expected 0, actual what is owed (only a sponsor who owes something: re-review L3). It is the record of an erased sponsor's
--   fees owed (their overdue reminders stop, §9 M4).
-- ERASED_NO_CLOSURE (attention, review M1): an erasure qb.erasure.close.alert-days or more ago of a user who had the
--   platform fee account (a CASH or FEE_CREDIT wallet, a grant, an invoice, a prepayment, a postpaid fee, a
--   manual credit or a fee request under review: the definition
--   QB_SPONSOR_CLOSURE_REGISTER uses) with no closure: the registration's event was lost. Expected 1, actual 0.
SELECT CAST('ERASED_GIFT_LEFT' AS varchar(30)) AS checkCode,
       CAST(c.{{QbSponsorClosure.sponsorId}} AS varchar(128)) AS sponsorId,
       CAST(NULL AS varchar(128)) AS reference, CAST(0 AS numeric) AS expected,
       CAST(sum(g.{{QbFeeCreditGrant.remaining}}) AS numeric) AS actual
FROM {{QbSponsorClosure}} c
JOIN {{QbFeeCreditGrant}} g ON g.{{QbFeeCreditGrant.sponsorId}} = c.{{QbSponsorClosure.sponsorId}}
WHERE c.{{QbSponsorClosure.status}} = 'CLOSED'
  AND g.{{QbFeeCreditGrant.remaining}} > 0
  AND g.{{QbFeeCreditGrant.expiresAt}} > c.{{QbSponsorClosure.closedAt}}
GROUP BY c.{{QbSponsorClosure.sponsorId}}
UNION ALL
SELECT CAST('ERASED_CLOSE_OPEN' AS varchar(30)), CAST(c.{{QbSponsorClosure.sponsorId}} AS varchar(128)),
       CAST(NULL AS varchar(128)), CAST(0 AS numeric),
       CAST(floor(extract(epoch FROM (CAST(:at AS timestamptz) - c.{{QbSponsorClosure.erasedAt}})) / 86400) AS numeric)
FROM {{QbSponsorClosure}} c
WHERE c.{{QbSponsorClosure.status}} = 'WAITING'
  AND CAST(:at AS timestamptz) IS NOT NULL
  AND c.{{QbSponsorClosure.erasedAt}} + make_interval(days => (CASE WHEN CAST(:alertDays AS numeric) BETWEEN 1 AND 60
        AND CAST(:alertDays AS numeric) = trunc(CAST(:alertDays AS numeric)) THEN CAST(:alertDays AS int) ELSE 0 END))
      <= CAST(:at AS timestamptz)
UNION ALL
SELECT owed.checkCode, owed.sponsorId, owed.reference, owed.expected, owed.actual FROM (
SELECT CAST('ERASED_FEE_OWED' AS varchar(30)) AS checkCode, CAST(c.{{QbSponsorClosure.sponsorId}} AS varchar(128)) AS sponsorId,
       CAST(NULL AS varchar(128)) AS reference, CAST(0 AS numeric) AS expected,
       CAST(coalesce((SELECT sum(i.{{QbFeeInvoice.total}} - i.{{QbFeeInvoice.creditApplied}} - i.{{QbFeeInvoice.paidAmount}})
                      FROM {{QbFeeInvoice}} i
                      WHERE i.{{QbFeeInvoice.sponsorId}} = c.{{QbSponsorClosure.sponsorId}}
                        AND i.{{QbFeeInvoice.status}} IN ('OPEN', 'PARTIAL', 'OVERDUE')), 0)
          + coalesce((SELECT sum(f.{{QbFeeCharge.total}}) FROM {{QbFeeCharge}} f
                      WHERE f.{{QbFeeCharge.sponsorId}} = c.{{QbSponsorClosure.sponsorId}}
                        AND f.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                        AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} ni
                                        WHERE ni.{{QbFeeInvoice.sponsorId}} = f.{{QbFeeCharge.sponsorId}}
                                          AND ((ni.{{QbFeeInvoice.period}} = f.{{QbFeeCharge.period}}
                                                AND ni.{{QbFeeInvoice.chargeId}} IS NULL)
                                               OR ni.{{QbFeeInvoice.chargeId}} = CAST(f.{{QbFeeCharge.chargeId}} AS text)))), 0)
          AS numeric) AS actual
FROM {{QbSponsorClosure}} c
WHERE c.{{QbSponsorClosure.status}} = 'CLOSED'
) owed
WHERE owed.actual > 0
UNION ALL
SELECT CAST('ERASED_NO_CLOSURE' AS varchar(30)), CAST(e.{{Erasure.userId}} AS varchar(128)),
       CAST(NULL AS varchar(128)), CAST(1 AS numeric), CAST(0 AS numeric)
FROM {{Erasure}} e
WHERE CAST(:at AS timestamptz) IS NOT NULL
  AND e.{{Erasure.erasedTime}} + make_interval(days => (CASE WHEN CAST(:alertDays AS numeric) BETWEEN 1 AND 60
        AND CAST(:alertDays AS numeric) = trunc(CAST(:alertDays AS numeric)) THEN CAST(:alertDays AS int) ELSE 0 END))
      <= CAST(:at AS timestamptz)
  AND NOT EXISTS (SELECT 1 FROM {{QbSponsorClosure}} nc
                  WHERE nc.{{QbSponsorClosure.sponsorId}} = CAST(e.{{Erasure.userId}} AS text))
  AND (EXISTS (SELECT 1 FROM {{QbWallet}} w
               WHERE w.{{QbWallet.ownerId}} = CAST(e.{{Erasure.userId}} AS text)
                 AND w.{{QbWallet.kind}} IN ('CASH', 'FEE_CREDIT'))
       OR EXISTS (SELECT 1 FROM {{QbFeeCreditGrant}} ng
                  WHERE ng.{{QbFeeCreditGrant.sponsorId}} = CAST(e.{{Erasure.userId}} AS text))
       OR EXISTS (SELECT 1 FROM {{QbFeeInvoice}} ni
                  WHERE ni.{{QbFeeInvoice.sponsorId}} = CAST(e.{{Erasure.userId}} AS text))
       OR EXISTS (SELECT 1 FROM {{QbPrepayment}} np
                  WHERE np.{{QbPrepayment.sponsorId}} = CAST(e.{{Erasure.userId}} AS text))
       OR EXISTS (SELECT 1 FROM {{QbFeeCharge}} nf
                  WHERE nf.{{QbFeeCharge.sponsorId}} = CAST(e.{{Erasure.userId}} AS text)
                    AND nf.{{QbFeeCharge.billingMode}} = 'POSTPAID')
       OR EXISTS (SELECT 1 FROM {{QbTopUp}} nt
                  WHERE nt.{{QbTopUp.sponsorId}} = CAST(e.{{Erasure.userId}} AS text)
                    AND nt.{{QbTopUp.method}} = 'MANUAL' AND nt.{{QbTopUp.status}} = 'REVIEW')
       OR EXISTS (SELECT 1 FROM {{QbFeeRequest}} nr
                  WHERE nr.{{QbFeeRequest.sponsorId}} = CAST(e.{{Erasure.userId}} AS text)
                    AND nr.{{QbFeeRequest.status}} = 'REVIEW'))
