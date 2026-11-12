/*---
id: qb.system.recon-fee-account
description: Whether the account that pays the platform's fees agrees with its lots and payments, by sponsor (F-M1) - the fee credit (2190) with its wallet and grants, the cash (2160) with its lots, and the fee payments posted with their parts; by operator (F-M2) the promotion expenses of the grants (5140) with what was granted less what was voided; and (F-S4b) by sponsor the lighting grants with the lit records that earned them; and (F-M3) the top-ups of verified companies with their verifications and certified amounts; and (F-M4) by operator its account (2140) with its settlements, withdrawals and transfers; and (F-M5) by sponsor the prepayments (2180) with what their open prepayments hold, and no prepayment held for a publication back in its draft; and (F-M6) the distribution fee's deposits, settlements and prices (the daily reconciliation; differences only, and one note)
entities: [QbWallet, QbFeeCreditGrant, QbCashLot, QbFeePayment, LedgerEntry, LedgerAccount, LedgerTransaction, QbTreeLit, QbTreeLitRevocation, QbTree, QbSponsorVerification, QbSponsor, QbOperatorSettlement, QbOperatorWithdrawal, QbOperatorTransfer, QbPrepayment, QbPublication, QbPublicationStartCounter, SysParam, QbPublicationCard, QbDistributionSettlement, QbDistributionPrice]
datasets:
  QbWallet: urn:jabiz:dataset:default:QbWallet
  QbFeeCreditGrant: urn:jabiz:dataset:default:QbFeeCreditGrant
  QbCashLot: urn:jabiz:dataset:default:QbCashLot
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
  QbTreeLit: urn:jabiz:dataset:default:QbTreeLit
  QbTreeLitRevocation: urn:jabiz:dataset:default:QbTreeLitRevocation
  QbTree: urn:jabiz:dataset:default:QbTree
  QbSponsorVerification: urn:jabiz:dataset:default:QbSponsorVerification
  QbSponsor: urn:jabiz:dataset:default:QbSponsor
  QbOperatorSettlement: urn:jabiz:dataset:default:QbOperatorSettlement
  QbOperatorWithdrawal: urn:jabiz:dataset:default:QbOperatorWithdrawal
  QbOperatorTransfer: urn:jabiz:dataset:default:QbOperatorTransfer
  QbPrepayment: urn:jabiz:dataset:default:QbPrepayment
  QbPublication: urn:jabiz:dataset:default:QbPublication
  QbPublicationStartCounter: urn:jabiz:dataset:default:QbPublicationStartCounter
  SysParam: urn:jabiz:dataset:now:SysParam
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbDistributionSettlement: urn:jabiz:dataset:default:QbDistributionSettlement
  QbDistributionPrice: urn:jabiz:dataset:default:QbDistributionPrice
params:
  at: { like: QbPublication.endedAt, description: "The run's time: an ended publication's prepayment still open a while after it is a difference (none: not checked)" }
  zone: { kind: { type: text, maxLength: 64 }, description: "F-M6: the fiscal year's zone, whose months the distribution fee is settled by (none: the unsettled months are not checked)" }
  unsettledHours: { kind: { type: numeric, precision: 3, scale: 0 }, description: "F-M6: qb.distribution.unsettled-after-hours (none: the unsettled months are not checked)" }
  missingHours: { kind: { type: numeric, precision: 3, scale: 0 }, description: "F-M6: qb.distribution.final-missing-after-hours (none: the missing final rows are not checked)" }
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
-- One statement, one snapshot: every payment posts, writes its parts and changes the lots and wallets in one
-- transaction under the sponsor's wallet lock.
-- FEE_CREDIT: 2190 for a sponsor (credit less debit) against their FEE_CREDIT wallet and against what their grants have
--   left (the wallet when it differs, else the grants).
-- CASH_LOTS: 2160 for a sponsor against what their cash lots have left.
-- FEE_CREDIT_PROMOTION (F-M2): 5140 posted from the grants (debit less credit) by the operator each grant recorded when
--   it was made, against the grants' amounts less what was voided of them; sponsorId holds the operator ('-': none).
-- FEE_PAYMENTS: what the postings of fees and invoices took from 2160 and 2190 for a sponsor (debit less credit: a
--   voided invoice's reversal gives it back) against the parts written for those postings. A payment from before F-M1
--   has no parts: a posting no part names, of a payment made before the first part was written, is left out, and so is
--   its reversal (the plan's §4: the parts are checked from F-M1 on).
-- LIGHT_GRANTS (F-S4b): for a sponsor, the lots of the source LIGHT (fee.LightGrants) no lit record of theirs earned:
--   a lot's key is a template's node (light:<template>/<node>) they lit, in the template or a copy of it, with a grant.
-- LIGHT_VOIDS (F-S4b): for a sponsor, what is left of LIGHT lots whose node they no longer hold lit (every lit record
--   of it revoked): the settlement voids it (the revocation's event or the daily qb.tree-grant-retry), also after a
--   voided invoice gave something back to it. A grant still to be given is not a difference (the switch or the
--   Sponsor's standing may hold it): qb.admin.tree-grants-pending lists them.
-- CERTIFIED_GRANTS (F-M3): for a sponsor, a top-up (source CERTIFIED_TOP_UP) though they were never verified (no
--   verification of theirs was ever APPROVED: verifiedAt is written by the approval only and kept after a revocation
--   or a new submission; an erased Sponsor's verifications go with the account and are not checked), expected 0 and
--   actual the top-up; and the onboarding plus the top-up above the certified amount the top-up recorded when it was
--   granted (expected that amount, actual the two).
-- OPERATOR_ACCOUNT (F-M4): for an operator (sponsorId holds the operator), 2140 posted from its settlements,
--   withdrawals and transfers (credit less debit, each connected to the operator by its source) against the records:
--   every settlement's payable less those paid on their own (PAID, before accounts existed), the withdrawals paid and
--   the transfers to its direct Sponsor.
-- PREPAYMENTS (F-M5): 2180 for a sponsor (credit less debit) against what their open prepaid prepayments hold.
-- PREPAYMENT_STALE (F-M5): by sponsor, the open prepayments that should have been given back: of a publication back
--   in its draft (withdrawn, rejected, its review expired), of one ended more than qb.prepayment.release.overdue-days
--   before :at (the daily job gives it back within a day; 1 to 30, anything else or none: 2), or of a publication that
--   is not there: expected 0, actual what they hold (a postpaid one: what it still counts).
-- PREPAYMENT_UNCHARGED (F-M5 recheck N1): by sponsor, the audience fees not charged though the publication started
--   (live or underfunded, or with a start already): expected 0, actual the fees before tax.
-- DISTRIBUTION_DEPOSITS (F-M6): by sponsor and publication (the reference), 2180 on the lines of the purpose
--   DISTRIBUTION (dimension 3) against what the publication's open prepaid DISTRIBUTION prepayment holds.
-- DISTRIBUTION_UNSETTLED (F-M6): by sponsor and month (the reference), every month before :at's a publication with a
--   price per start ran in without the month's row or a final one, qb.distribution.unsettled-after-hours (handed in) after the
--   month's end (the condition of qb.system.distribution-due and of the invoices' readiness): expected 0, actual how
--   many publications. Not checked without :zone.
-- DISTRIBUTION_FINAL_MISSING (F-M6): by sponsor, the ended publications with a price per start and no final row
--   qb.distribution.final-missing-after-hours (handed in) after their end: expected 0, actual how many.
-- DISTRIBUTION_PRICE_OUT_OF_RANGE (F-M6): by operator (sponsorId holds it), a price outside the headquarters' range as
--   the parameters give it now (a bound not set does not bound): expected 0, actual 1.
-- DISTRIBUTION_AT_FLOOR (F-M6, a note, not a difference): by sponsor, the publications not ended whose price per start
--   is the operator's floor (their top tier set it no higher): expected 0, actual how many.
-- PREPAYMENT_PARTS (F-M5 review M3): by sponsor, the prepaid prepayments whose parts (set aside, given back, less
--   what fees took of them) do not add up to what they hold: expected what they hold, actual what the parts say.
WITH credit_ledger AS (
    SELECT e.{{LedgerEntry.dimension1}} AS r_party,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_balance
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2190'
    GROUP BY e.{{LedgerEntry.dimension1}}
),
credit_wallets AS (
    SELECT w.{{QbWallet.ownerId}} AS r_party, w.{{QbWallet.balance}} AS r_balance
    FROM {{QbWallet}} w
    WHERE w.{{QbWallet.kind}} = 'FEE_CREDIT'
),
grants AS (
    SELECT g.{{QbFeeCreditGrant.sponsorId}} AS r_party, sum(g.{{QbFeeCreditGrant.remaining}}) AS r_remaining
    FROM {{QbFeeCreditGrant}} g
    GROUP BY g.{{QbFeeCreditGrant.sponsorId}}
),
cash_ledger AS (
    SELECT e.{{LedgerEntry.dimension1}} AS r_party,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_balance
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2160'
    GROUP BY e.{{LedgerEntry.dimension1}}
),
lots AS (
    SELECT l.{{QbCashLot.sponsorId}} AS r_party, sum(l.{{QbCashLot.remaining}}) AS r_remaining
    FROM {{QbCashLot}} l
    GROUP BY l.{{QbCashLot.sponsorId}}
),
parts AS (
    SELECT p.{{QbFeePayment.transactionId}} AS r_tx, sum(p.{{QbFeePayment.amount}}) AS r_amount
    FROM {{QbFeePayment}} p
    GROUP BY p.{{QbFeePayment.transactionId}}
),
prepayment_ledger AS (
    SELECT e.{{LedgerEntry.dimension1}} AS r_party,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_balance
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2180'
    GROUP BY e.{{LedgerEntry.dimension1}}
),
distribution_ledger AS (
    -- M4 (review): by sponsor and publication (dimension 2), so that two publications' errors cannot cancel out.
    SELECT e.{{LedgerEntry.dimension1}} AS r_party, e.{{LedgerEntry.dimension2}} AS r_publication,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_balance
    FROM {{LedgerEntry}} e
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2180' AND e.{{LedgerEntry.dimension3}} = 'DISTRIBUTION'
    GROUP BY e.{{LedgerEntry.dimension1}}, e.{{LedgerEntry.dimension2}}
),
distribution_held AS (
    SELECT p.{{QbPrepayment.sponsorId}} AS r_party, CAST(p.{{QbPrepayment.publicationId}} AS text) AS r_publication,
           sum(p.{{QbPrepayment.remaining}}) AS r_remaining
    FROM {{QbPrepayment}} p
    WHERE p.{{QbPrepayment.status}} = 'OPEN' AND p.{{QbPrepayment.mode}} = 'PREPAID'
      AND p.{{QbPrepayment.purpose}} = 'DISTRIBUTION'
    GROUP BY p.{{QbPrepayment.sponsorId}}, p.{{QbPrepayment.publicationId}}
),
distribution_params AS (
    -- L4 (review): the two hours are QbParams' values handed in by the reconciliation (no default here); either
    -- missing, its check is not made (SetupParamsCheck reports the missing parameter).
    SELECT CAST(:unsettledHours AS int) AS unsettled_hours,
           CAST(:missingHours AS int) AS missing_hours,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.distribution.rate.min' THEN p.{{SysParam.value}} END) AS rate_min,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.distribution.rate.max' THEN p.{{SysParam.value}} END) AS rate_max,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.distribution.unit-floor.min' THEN p.{{SysParam.value}} END)
               AS floor_min,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.distribution.unit-floor.max' THEN p.{{SysParam.value}} END)
               AS floor_max,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.distribution.unit-ceiling.max' THEN p.{{SysParam.value}} END)
               AS ceiling_max,
           max(CASE WHEN p.{{SysParam.paramKey}} = 'qb.distribution.minimum-deposit.max' THEN p.{{SysParam.value}} END)
               AS deposit_max
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} LIKE 'qb.distribution.%'
),
distribution_month AS (
    -- The month before :at in :zone: its first instant, the next month's, and its name.
    SELECT (date_trunc('month', CAST(:at AS timestamptz) AT TIME ZONE CAST(:zone AS text)) - interval '1 month')
               AT TIME ZONE CAST(:zone AS text) AS m_start,
           date_trunc('month', CAST(:at AS timestamptz) AT TIME ZONE CAST(:zone AS text))
               AT TIME ZONE CAST(:zone AS text) AS m_end,
           to_char(date_trunc('month', CAST(:at AS timestamptz) AT TIME ZONE CAST(:zone AS text)) - interval '1 month',
               'YYYY-MM') AS m_name
    WHERE CAST(:at AS timestamptz) IS NOT NULL AND CAST(:zone AS text) IS NOT NULL
),
prepayments_held AS (
    SELECT p.{{QbPrepayment.sponsorId}} AS r_party, sum(p.{{QbPrepayment.remaining}}) AS r_remaining
    FROM {{QbPrepayment}} p
    WHERE p.{{QbPrepayment.status}} = 'OPEN' AND p.{{QbPrepayment.mode}} = 'PREPAID'
    GROUP BY p.{{QbPrepayment.sponsorId}}
),
promotion_ledger AS (
    SELECT coalesce(g.{{QbFeeCreditGrant.operatorId}}, '-') AS r_operator,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_balance
    FROM {{LedgerTransaction}} t
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    LEFT JOIN {{QbFeeCreditGrant}} g
        ON CAST(g.{{QbFeeCreditGrant.grantId}} AS text) = t.{{LedgerTransaction.sourceId}}
    WHERE t.{{LedgerTransaction.sourceEntity}} = 'QbFeeCreditGrant'
      AND a.{{LedgerAccount.accountCode}} = '5140'
    GROUP BY coalesce(g.{{QbFeeCreditGrant.operatorId}}, '-')
),
promotion_grants AS (
    SELECT coalesce(g.{{QbFeeCreditGrant.operatorId}}, '-') AS r_operator,
           sum(g.{{QbFeeCreditGrant.amount}} - g.{{QbFeeCreditGrant.voidedAmount}}) AS r_net
    FROM {{QbFeeCreditGrant}} g
    GROUP BY coalesce(g.{{QbFeeCreditGrant.operatorId}}, '-')
),
first_part AS (
    SELECT min(p.{{QbFeePayment.paidAt}}) AS r_at FROM {{QbFeePayment}} p
),
fee_tx AS (
    SELECT CAST(t.{{LedgerTransaction.transactionId}} AS text) AS r_tx,
           coalesce(t.{{LedgerTransaction.reversesTransactionId}}, t.{{LedgerTransaction.transactionId}}) AS r_original,
           e.{{LedgerEntry.dimension1}} AS r_party,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'DEBIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_taken
    FROM {{LedgerTransaction}} t
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    WHERE t.{{LedgerTransaction.sourceEntity}} IN ('QbFeeCharge', 'QbFeeInvoice')
      AND a.{{LedgerAccount.accountCode}} IN ('2160', '2190')
    GROUP BY t.{{LedgerTransaction.transactionId}}, t.{{LedgerTransaction.reversesTransactionId}},
             e.{{LedgerEntry.dimension1}}
),
checked_tx AS (
    SELECT f.r_party, f.r_taken, coalesce(pt.r_amount, 0) AS r_parts
    FROM fee_tx f
    LEFT JOIN parts pt ON pt.r_tx = f.r_tx
    JOIN {{LedgerTransaction}} o ON o.{{LedgerTransaction.transactionId}} = f.r_original
    CROSS JOIN first_part fp
    WHERE pt.r_tx IS NOT NULL
       OR EXISTS (SELECT 1 FROM parts q WHERE q.r_tx = CAST(f.r_original AS text))
       OR NOT (fp.r_at IS NULL OR o.{{LedgerTransaction.createdTime}} < fp.r_at)
),
light_lits AS (
    SELECT l.{{QbTreeLit.subjectId}} AS r_party,
           'light:' || CASE WHEN t.{{QbTree.ownerKind}} = 'PLATFORM' THEN CAST(t.{{QbTree.treeId}} AS text)
                      ELSE t.{{QbTree.templateTreeId}} END || '/' || l.{{QbTreeLit.nodeId}} AS r_key,
           NOT EXISTS (SELECT 1 FROM {{QbTreeLitRevocation}} r
                       WHERE r.{{QbTreeLitRevocation.litId}} = CAST(l.{{QbTreeLit.litId}} AS text)) AS r_held
    FROM {{QbTreeLit}} l
    JOIN {{QbTree}} t ON CAST(t.{{QbTree.treeId}} AS text) = l.{{QbTreeLit.treeId}}
    WHERE l.{{QbTreeLit.subjectKind}} = 'SPONSOR' AND l.{{QbTreeLit.grantAmount}} > 0
),
light_unearned AS (
    SELECT g.{{QbFeeCreditGrant.sponsorId}} AS r_party, sum(g.{{QbFeeCreditGrant.amount}}) AS r_amount
    FROM {{QbFeeCreditGrant}} g
    WHERE g.{{QbFeeCreditGrant.source}} = 'LIGHT'
      AND NOT EXISTS (SELECT 1 FROM light_lits x WHERE x.r_party = g.{{QbFeeCreditGrant.sponsorId}}
                                                  AND x.r_key = g.{{QbFeeCreditGrant.sourceKey}})
    GROUP BY g.{{QbFeeCreditGrant.sponsorId}}
),
operator_ledger AS (
    SELECT coalesce(st.{{QbOperatorSettlement.operatorId}}, wd.{{QbOperatorWithdrawal.operatorId}},
                    tr.{{QbOperatorTransfer.operatorId}}, '-') AS r_operator,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_balance
    FROM {{LedgerTransaction}} t
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    LEFT JOIN {{QbOperatorSettlement}} st ON t.{{LedgerTransaction.sourceEntity}} = 'QbOperatorSettlement'
        AND CAST(st.{{QbOperatorSettlement.settlementId}} AS text) = t.{{LedgerTransaction.sourceId}}
    LEFT JOIN {{QbOperatorWithdrawal}} wd ON t.{{LedgerTransaction.sourceEntity}} = 'QbOperatorWithdrawal'
        AND CAST(wd.{{QbOperatorWithdrawal.withdrawalId}} AS text) = t.{{LedgerTransaction.sourceId}}
    LEFT JOIN {{QbOperatorTransfer}} tr ON t.{{LedgerTransaction.sourceEntity}} = 'QbOperatorTransfer'
        AND CAST(tr.{{QbOperatorTransfer.transferId}} AS text) = t.{{LedgerTransaction.sourceId}}
    WHERE a.{{LedgerAccount.accountCode}} = '2140'
    GROUP BY 1
),
operator_records AS (
    SELECT r.r_operator, sum(r.r_amount) AS r_balance
    FROM (
        SELECT s.{{QbOperatorSettlement.operatorId}} AS r_operator,
               s.{{QbOperatorSettlement.payable}}
               - CASE WHEN s.{{QbOperatorSettlement.status}} = 'PAID' THEN s.{{QbOperatorSettlement.payable}}
                      ELSE 0 END AS r_amount
        FROM {{QbOperatorSettlement}} s
        UNION ALL
        SELECT w.{{QbOperatorWithdrawal.operatorId}}, -w.{{QbOperatorWithdrawal.amount}}
        FROM {{QbOperatorWithdrawal}} w
        WHERE w.{{QbOperatorWithdrawal.status}} = 'PAID'
        UNION ALL
        SELECT x.{{QbOperatorTransfer.operatorId}}, -x.{{QbOperatorTransfer.amount}}
        FROM {{QbOperatorTransfer}} x) r
    GROUP BY r.r_operator
),
light_left AS (
    SELECT g.{{QbFeeCreditGrant.sponsorId}} AS r_party, sum(g.{{QbFeeCreditGrant.remaining}}) AS r_amount
    FROM {{QbFeeCreditGrant}} g
    WHERE g.{{QbFeeCreditGrant.source}} = 'LIGHT' AND g.{{QbFeeCreditGrant.remaining}} > 0
      AND NOT EXISTS (SELECT 1 FROM light_lits x WHERE x.r_party = g.{{QbFeeCreditGrant.sponsorId}}
                                                  AND x.r_key = g.{{QbFeeCreditGrant.sourceKey}} AND x.r_held)
    GROUP BY g.{{QbFeeCreditGrant.sponsorId}}
)
SELECT CAST('FEE_CREDIT' AS varchar(30)) AS checkCode,
       CAST(coalesce(l.r_party, w.r_party, g.r_party) AS varchar(128)) AS sponsorId,
       CAST(NULL AS varchar(128)) AS reference,
       coalesce(l.r_balance, 0) AS expected,
       CASE WHEN coalesce(w.r_balance, 0) <> coalesce(l.r_balance, 0) THEN coalesce(w.r_balance, 0)
            ELSE coalesce(g.r_remaining, 0) END AS actual
FROM credit_ledger l
FULL OUTER JOIN credit_wallets w ON w.r_party = l.r_party
FULL OUTER JOIN grants g ON g.r_party = coalesce(l.r_party, w.r_party)
WHERE coalesce(w.r_balance, 0) <> coalesce(l.r_balance, 0) OR coalesce(g.r_remaining, 0) <> coalesce(l.r_balance, 0)
UNION ALL
SELECT CAST('CASH_LOTS' AS varchar(30)), CAST(coalesce(c.r_party, x.r_party) AS varchar(128)), CAST(NULL AS varchar(128)),
       coalesce(c.r_balance, 0), coalesce(x.r_remaining, 0)
FROM cash_ledger c
FULL OUTER JOIN lots x ON x.r_party = c.r_party
WHERE coalesce(c.r_balance, 0) <> coalesce(x.r_remaining, 0)
UNION ALL
SELECT CAST('FEE_CREDIT_PROMOTION' AS varchar(30)), CAST(coalesce(pg.r_operator, pl.r_operator) AS varchar(128)), CAST(NULL AS varchar(128)),
       coalesce(pg.r_net, 0), coalesce(pl.r_balance, 0)
FROM promotion_grants pg
FULL OUTER JOIN promotion_ledger pl ON pl.r_operator = pg.r_operator
WHERE coalesce(pg.r_net, 0) <> coalesce(pl.r_balance, 0)
UNION ALL
SELECT CAST('FEE_PAYMENTS' AS varchar(30)), CAST(k.r_party AS varchar(128)), CAST(NULL AS varchar(128)), sum(k.r_taken), sum(k.r_parts)
FROM checked_tx k
GROUP BY k.r_party
HAVING sum(k.r_taken) <> sum(k.r_parts)
UNION ALL
SELECT CAST('LIGHT_GRANTS' AS varchar(30)), CAST(u.r_party AS varchar(128)), CAST(NULL AS varchar(128)), CAST(0 AS numeric), u.r_amount
FROM light_unearned u
UNION ALL
SELECT CAST('LIGHT_VOIDS' AS varchar(30)), CAST(v.r_party AS varchar(128)), CAST(NULL AS varchar(128)), CAST(0 AS numeric), v.r_amount
FROM light_left v
UNION ALL
SELECT CAST('OPERATOR_ACCOUNT' AS varchar(30)), CAST(coalesce(orc.r_operator, ol.r_operator) AS varchar(128)), CAST(NULL AS varchar(128)),
       coalesce(orc.r_balance, 0), coalesce(ol.r_balance, 0)
FROM operator_records orc
FULL OUTER JOIN operator_ledger ol ON ol.r_operator = orc.r_operator
WHERE coalesce(orc.r_balance, 0) <> coalesce(ol.r_balance, 0)
UNION ALL
SELECT CAST('CERTIFIED_GRANTS' AS varchar(30)), CAST(t.{{QbFeeCreditGrant.sponsorId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), t.{{QbFeeCreditGrant.amount}}
FROM {{QbFeeCreditGrant}} t
JOIN {{QbSponsor}} sp ON sp.{{QbSponsor.userId}} = t.{{QbFeeCreditGrant.sponsorId}}
WHERE t.{{QbFeeCreditGrant.source}} = 'CERTIFIED_TOP_UP'
  AND NOT EXISTS (SELECT 1 FROM {{QbSponsorVerification}} v
                  WHERE v.{{QbSponsorVerification.sponsorId}} = t.{{QbFeeCreditGrant.sponsorId}}
                    AND v.{{QbSponsorVerification.verifiedAt}} IS NOT NULL)
UNION ALL
SELECT CAST('CERTIFIED_GRANTS' AS varchar(30)), CAST(t.{{QbFeeCreditGrant.sponsorId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       coalesce(t.{{QbFeeCreditGrant.certifiedAmount}}, o.{{QbFeeCreditGrant.certifiedAmount}}, CAST(0 AS numeric)),
       o.{{QbFeeCreditGrant.amount}} + t.{{QbFeeCreditGrant.amount}}
FROM {{QbFeeCreditGrant}} t
JOIN {{QbFeeCreditGrant}} o ON o.{{QbFeeCreditGrant.sponsorId}} = t.{{QbFeeCreditGrant.sponsorId}}
    AND o.{{QbFeeCreditGrant.sourceKey}} = 'onboarding'
WHERE t.{{QbFeeCreditGrant.source}} = 'CERTIFIED_TOP_UP'
  AND o.{{QbFeeCreditGrant.amount}} + t.{{QbFeeCreditGrant.amount}}
      > coalesce(t.{{QbFeeCreditGrant.certifiedAmount}}, o.{{QbFeeCreditGrant.certifiedAmount}}, CAST(0 AS numeric))
UNION ALL
SELECT CAST('PREPAYMENTS' AS varchar(30)), CAST(coalesce(ph.r_party, pl.r_party) AS varchar(128)), CAST(NULL AS varchar(128)),
       coalesce(ph.r_remaining, 0), coalesce(pl.r_balance, 0)
FROM prepayments_held ph
FULL OUTER JOIN prepayment_ledger pl ON pl.r_party = ph.r_party
WHERE coalesce(ph.r_remaining, 0) <> coalesce(pl.r_balance, 0)
UNION ALL
SELECT CAST('PREPAYMENT_STALE' AS varchar(30)), CAST(pp.{{QbPrepayment.sponsorId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), sum(pp.{{QbPrepayment.remaining}})
FROM {{QbPrepayment}} pp
LEFT JOIN {{QbPublication}} pub ON CAST(pub.{{QbPublication.publicationId}} AS text) = pp.{{QbPrepayment.publicationId}}
CROSS JOIN (
    SELECT coalesce(max(CASE WHEN p.{{SysParam.value}} ~ '^[0-9]{1,2}$'
                                  AND CAST(p.{{SysParam.value}} AS int) BETWEEN 1 AND 30
                             THEN CAST(p.{{SysParam.value}} AS int) END), 2) AS days
    FROM {{SysParam}} p
    WHERE p.{{SysParam.paramKey}} = 'qb.prepayment.release.overdue-days') params
WHERE pp.{{QbPrepayment.status}} = 'OPEN'
  AND pp.{{QbPrepayment.publicationId}} IS NOT NULL
  AND (pub.{{QbPublication.publicationId}} IS NULL
       OR pub.{{QbPublication.status}} = 'DRAFT'
       OR (pub.{{QbPublication.status}} = 'ENDED' AND CAST(:at AS timestamptz) IS NOT NULL
           AND pub.{{QbPublication.endedAt}} < CAST(:at AS timestamptz) - make_interval(days => params.days)))
GROUP BY pp.{{QbPrepayment.sponsorId}}
UNION ALL
SELECT CAST('PREPAYMENT_PARTS' AS varchar(30)), CAST(pp.{{QbPrepayment.sponsorId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       sum(pp.{{QbPrepayment.remaining}}), sum(coalesce(own.r_amount, 0) - coalesce(taken.r_amount, 0))
FROM {{QbPrepayment}} pp
LEFT JOIN (SELECT f.{{QbFeePayment.targetId}} AS r_id, sum(f.{{QbFeePayment.amount}}) AS r_amount
           FROM {{QbFeePayment}} f
           WHERE f.{{QbFeePayment.targetEntity}} = 'QbPrepayment'
           GROUP BY f.{{QbFeePayment.targetId}}) own ON own.r_id = pp.{{QbPrepayment.prepaymentId}}
-- What fees took of it: its positive parts only (a voided invoice gives what it paid back to the account, not to the
-- prepayment: its negative parts name the prepayment too).
LEFT JOIN (SELECT f.{{QbFeePayment.prepaymentId}} AS r_id, sum(f.{{QbFeePayment.amount}}) AS r_amount
           FROM {{QbFeePayment}} f
           WHERE f.{{QbFeePayment.prepaymentId}} IS NOT NULL AND f.{{QbFeePayment.amount}} > 0
           GROUP BY f.{{QbFeePayment.prepaymentId}}) taken ON taken.r_id = pp.{{QbPrepayment.prepaymentId}}
WHERE pp.{{QbPrepayment.mode}} = 'PREPAID'
  AND pp.{{QbPrepayment.remaining}} <> coalesce(own.r_amount, 0) - coalesce(taken.r_amount, 0)
GROUP BY pp.{{QbPrepayment.sponsorId}}
UNION ALL
SELECT CAST('PREPAYMENT_UNCHARGED' AS varchar(30)), CAST(pp.{{QbPrepayment.sponsorId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), sum(pp.{{QbPrepayment.chargeAmount}})
FROM {{QbPrepayment}} pp
JOIN {{QbPublication}} pub ON CAST(pub.{{QbPublication.publicationId}} AS text) = pp.{{QbPrepayment.publicationId}}
LEFT JOIN {{QbPublicationStartCounter}} sc
    ON sc.{{QbPublicationStartCounter.publicationId}} = pub.{{QbPublication.publicationId}}
WHERE pp.{{QbPrepayment.purpose}} = 'AUDIENCE'
  AND pp.{{QbPrepayment.status}} = 'OPEN'
  AND pp.{{QbPrepayment.chargedAt}} IS NULL
  AND pp.{{QbPrepayment.chargeAmount}} > 0
  AND (pub.{{QbPublication.status}} IN ('LIVE', 'FUNDS_SHORT') OR coalesce(sc.{{QbPublicationStartCounter.starts}}, 0) > 0)
GROUP BY pp.{{QbPrepayment.sponsorId}}
UNION ALL
SELECT CAST('DISTRIBUTION_DEPOSITS' AS varchar(30)), CAST(coalesce(dh.r_party, dl.r_party) AS varchar(128)),
       CAST(coalesce(dh.r_publication, dl.r_publication) AS varchar(128)),
       coalesce(dh.r_remaining, 0), coalesce(dl.r_balance, 0)
FROM distribution_held dh
FULL OUTER JOIN distribution_ledger dl ON dl.r_party = dh.r_party AND dl.r_publication = dh.r_publication
WHERE coalesce(dh.r_remaining, 0) <> coalesce(dl.r_balance, 0)
UNION ALL
SELECT CAST('DISTRIBUTION_UNSETTLED' AS varchar(30)), CAST(dp.{{QbPublication.publisherId}} AS varchar(128)),
       CAST(to_char(mo.r_month, 'YYYY-MM') AS varchar(128)), CAST(0 AS numeric), CAST(count(*) AS numeric)
FROM {{QbPublication}} dp
JOIN {{QbPublicationCard}} dc ON dc.{{QbPublicationCard.publicationId}} = dp.{{QbPublication.publicationId}}
CROSS JOIN distribution_month dm
CROSS JOIN distribution_params dpar
-- M2 (review): every month before this one, not only the last: from the month it started in to the month it ended in
-- (or the last month), each month without its row or a final one, once qb.distribution.unsettled-after-hours has
-- passed since that month's end. The months a postpaid sponsor's invoice waits for (qb.system.fee-invoices-due:
-- owed less ready) are among them, and so are a prepaid sponsor's.
CROSS JOIN LATERAL generate_series(
    -- When it went on (N1): greatest(its start, its approval); the same expression in qb.system.distribution-due, qb.system.fee-invoices-due and qb.system.recon-fee-account (DistributionDueConditionTest).
    date_trunc('month', greatest(dc.{{QbPublicationCard.startAt}}, dp.{{QbPublication.approvedAt}}) AT TIME ZONE CAST(:zone AS text)),
    date_trunc('month', least(coalesce(dc.{{QbPublicationCard.endedAt}}, dm.m_end - interval '1 second'),
                              dm.m_end - interval '1 second') AT TIME ZONE CAST(:zone AS text)),
    interval '1 month') AS mo(r_month)
WHERE dp.{{QbPublication.distributionUnit}} IS NOT NULL
  AND dp.{{QbPublication.publisherKind}} = 'SPONSOR'
  AND CAST(:at AS timestamptz) >= (mo.r_month + interval '1 month') AT TIME ZONE CAST(:zone AS text)
                                  + make_interval(hours => dpar.unsettled_hours)
  AND NOT EXISTS (SELECT 1 FROM {{QbDistributionSettlement}} ds
                  WHERE ds.{{QbDistributionSettlement.publicationId}} = CAST(dp.{{QbPublication.publicationId}} AS text)
                    AND (ds.{{QbDistributionSettlement.final}}
                         OR ds.{{QbDistributionSettlement.period}} = to_char(mo.r_month, 'YYYY-MM')))
GROUP BY dp.{{QbPublication.publisherId}}, mo.r_month
UNION ALL
SELECT CAST('DISTRIBUTION_FINAL_MISSING' AS varchar(30)), CAST(dp.{{QbPublication.publisherId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), CAST(count(*) AS numeric)
FROM {{QbPublication}} dp
JOIN {{QbPublicationCard}} dc ON dc.{{QbPublicationCard.publicationId}} = dp.{{QbPublication.publicationId}}
CROSS JOIN distribution_params dpar
WHERE dp.{{QbPublication.distributionUnit}} IS NOT NULL
  AND dp.{{QbPublication.publisherKind}} = 'SPONSOR'
  AND dc.{{QbPublicationCard.status}} = 'ENDED'
  AND CAST(:at AS timestamptz) IS NOT NULL
  AND dc.{{QbPublicationCard.endedAt}} < CAST(:at AS timestamptz) - make_interval(hours => dpar.missing_hours)
  AND NOT EXISTS (SELECT 1 FROM {{QbDistributionSettlement}} ds
                  WHERE ds.{{QbDistributionSettlement.publicationId}} = CAST(dp.{{QbPublication.publicationId}} AS text)
                    AND ds.{{QbDistributionSettlement.final}})
GROUP BY dp.{{QbPublication.publisherId}}
UNION ALL
SELECT CAST('DISTRIBUTION_PRICE_OUT_OF_RANGE' AS varchar(30)), CAST(pr.{{QbDistributionPrice.operatorId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), CAST(1 AS numeric)
FROM {{QbDistributionPrice}} pr
CROSS JOIN distribution_params dpar
WHERE (dpar.rate_min ~ '^[0-9]+(\.[0-9]+)?$' AND pr.{{QbDistributionPrice.rate}} < CAST(dpar.rate_min AS numeric))
   OR (dpar.rate_max ~ '^[0-9]+(\.[0-9]+)?$' AND pr.{{QbDistributionPrice.rate}} > CAST(dpar.rate_max AS numeric))
   OR (dpar.floor_min ~ '^[0-9]+(\.[0-9]+)?$'
       AND pr.{{QbDistributionPrice.unitFloor}} < CAST(dpar.floor_min AS numeric))
   OR (dpar.floor_max ~ '^[0-9]+(\.[0-9]+)?$'
       AND pr.{{QbDistributionPrice.unitFloor}} > CAST(dpar.floor_max AS numeric))
   OR (dpar.ceiling_max ~ '^[0-9]+(\.[0-9]+)?$'
       AND pr.{{QbDistributionPrice.unitCeiling}} > CAST(dpar.ceiling_max AS numeric))
   OR (dpar.deposit_max ~ '^[0-9]+(\.[0-9]+)?$'
       AND pr.{{QbDistributionPrice.minimumDeposit}} > CAST(dpar.deposit_max AS numeric))
UNION ALL
SELECT CAST('DISTRIBUTION_AT_FLOOR' AS varchar(30)), CAST(dp.{{QbPublication.publisherId}} AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), CAST(count(*) AS numeric)
FROM {{QbPublication}} dp
JOIN {{QbPublicationCard}} dc ON dc.{{QbPublicationCard.publicationId}} = dp.{{QbPublication.publicationId}}
WHERE dp.{{QbPublication.distributionAtFloor}}
  AND dp.{{QbPublication.publisherKind}} = 'SPONSOR'
  AND dc.{{QbPublicationCard.status}} <> 'ENDED'
GROUP BY dp.{{QbPublication.publisherId}}
