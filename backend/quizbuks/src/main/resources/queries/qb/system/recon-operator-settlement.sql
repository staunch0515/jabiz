/*---
id: qb.system.recon-operator-settlement
description: Whether the operators' settlements agree with their lines and with the ledger (F-M12 c) - the operators still without a settlement for a month and the step they wait for, what a settlement counted against its own columns and the fees and parts of payments made with granted credit that nothing counted, the granted credit taken back against 5140 and month by month, the fees without an operator, and (a note) the suspended operators that still carry credit to take back
entities: [QbOperator, QbOperatorSettlement, QbOperatorSettlementLine, QbFeeCharge, QbFeeInvoice, QbFeePayment, QbFeeCreditNote, QbPublication, QbPublicationCard, QbDistributionSettlement, LedgerEntry, LedgerAccount, LedgerTransaction]
datasets:
  QbOperator: urn:jabiz:dataset:default:QbOperator
  QbOperatorSettlement: urn:jabiz:dataset:default:QbOperatorSettlement
  QbOperatorSettlementLine: urn:jabiz:dataset:default:QbOperatorSettlementLine
  QbFeeCharge: urn:jabiz:dataset:default:QbFeeCharge
  QbFeeInvoice: urn:jabiz:dataset:default:QbFeeInvoice
  QbFeePayment: urn:jabiz:dataset:default:QbFeePayment
  QbFeeCreditNote: urn:jabiz:dataset:default:QbFeeCreditNote
  QbPublication: urn:jabiz:dataset:default:QbPublication
  QbPublicationCard: urn:jabiz:dataset:default:QbPublicationCard
  QbDistributionSettlement: urn:jabiz:dataset:default:QbDistributionSettlement
params:
  at: { like: QbOperatorSettlement.issuedAt, description: "The run's time: the months before the one it falls in are due (none: the waiting ones are not checked)" }
  zone: { kind: { type: text, maxLength: 64 }, description: "The fiscal year's zone, whose months the settlements are of (none: the waiting ones are not checked)" }
  waitingHours: { kind: { type: numeric, precision: 4, scale: 0 }, description: "F-M12 c: qb.operator.settlement.waiting-after-hours (none: the waiting ones are not checked)" }
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
-- The checks of the operators' settlement of F-M12 (docs/quizbuks/plans/impl/F-M12.md §6). sponsorId holds the operator
-- (the sponsor, for OPERATOR_CHARGE_NO_OPERATOR). Only the settlements of the new kind (covered_from is set) are
-- checked, and only fees charged since their switch point: what is before it is the old rule's (§3.0).
--
-- OPERATOR_SETTLEMENT_WAITING: an operator's earliest month without a settlement (head of the line: the months after
--   it wait for it, they are not listed) when :waitingHours hours have passed since that month's end, as the daily job's
--   items take it (qb.system.operator-settlements-due). The reference is "<month>:<steps>": the steps of the month that are
--   not done yet, the branches of qb.system.operator-settlement-readiness (DISTRIBUTION, INVOICE; the month alone when
--   nothing is pending, which is the job's own failure). Expected 0, actual how many months it is behind.
-- OPERATOR_SETTLEMENT_COVERAGE, reference "<month>:<column>": a settlement's column against what its lines say (the
--   platform fees and the part not shared of the CHARGE lines, the credit used and not shared in the batch of its fees,
--   what it took back and what is due of it, the share of the credit used before and of the voids, each rounded down once as
--   the settlement does); expected the lines', actual the column's.
--   Reference "fee:<period>": fees (expected their amount, actual 0) that two settlements which had to count them did not
--   (settled after they were charged, of their month or a later one; a capped settlement did not have to count the fees
--   after its watermark; a fee whose invoice is voided is never counted). One settlement cycle is allowed: a fee
--   charged while the settlement ran is counted by the next.
--   Reference "gift:<month paid>": parts of payments made with granted credit on a fee counted (expected the sum of what
--   they paid, actual 0) that two settlements did not write a line for (a capped one did not have to for the fees counted
--   before; a payment on fees of no positive total is never counted, which this shows).
-- GIFT_RECOVERY, reference "recovered": what the settlements say they took back against the net credit to 5140 of the
--   postings made for them (5140, not 2140: a refund that paid the cost carried posts that part to 4110, §4); reference
--   "<month>": what was due of the month against what was taken back and carried out less what was carried in.
-- OPERATOR_CHARGE_NO_OPERATOR: by sponsor and period, fees with no operator (no settlement counts them).
-- OPERATOR_GIFT_CARRY_OPEN (a note): a suspended operator whose latest settlement carries credit to take back.
WITH new_settlements AS (
    SELECT s.{{QbOperatorSettlement.settlementId}} AS r_id,
           CAST(s.{{QbOperatorSettlement.settlementId}} AS text) AS r_id_text,
           s.{{QbOperatorSettlement.operatorId}} AS r_operator,
           s.{{QbOperatorSettlement.month}} AS r_month,
           s.{{QbOperatorSettlement.issuedAt}} AS r_issued_at,
           s.{{QbOperatorSettlement.coveredFrom}} AS r_covered_from,
           s.{{QbOperatorSettlement.capped}} AS r_capped,
           s.{{QbOperatorSettlement.watermarkChargedAt}} AS r_mark_at,
           s.{{QbOperatorSettlement.watermarkChargeId}} AS r_mark_id,
           s.{{QbOperatorSettlement.platformFees}} AS r_platform_fees,
           s.{{QbOperatorSettlement.costExcluded}} AS r_cost_excluded,
           s.{{QbOperatorSettlement.giftPaid}} AS r_gift_paid,
           s.{{QbOperatorSettlement.giftShareDeduction}} AS r_gift_share_deduction,
           s.{{QbOperatorSettlement.voidDeduction}} AS r_void_deduction,
           s.{{QbOperatorSettlement.giftUsed}} AS r_gift_used,
           s.{{QbOperatorSettlement.giftDue}} AS r_gift_due,
           s.{{QbOperatorSettlement.giftCarriedIn}} AS r_gift_carried_in,
           s.{{QbOperatorSettlement.giftRecovered}} AS r_gift_recovered,
           s.{{QbOperatorSettlement.giftCarriedOut}} AS r_gift_carried_out
    FROM {{QbOperatorSettlement}} s
    WHERE s.{{QbOperatorSettlement.coveredFrom}} IS NOT NULL
),
line_sums AS (
    -- A credit-used line is "in the batch" when the settlement also counted its fee (a CHARGE line of its own).
    SELECT l.{{QbOperatorSettlementLine.settlementId}} AS r_id,
           coalesce(sum(l.{{QbOperatorSettlementLine.amount}})
               FILTER (WHERE l.{{QbOperatorSettlementLine.lineKind}} = 'CHARGE'), 0) AS r_fees,
           coalesce(sum(l.{{QbOperatorSettlementLine.excluded}})
               FILTER (WHERE l.{{QbOperatorSettlementLine.lineKind}} = 'CHARGE'), 0) AS r_excluded,
           coalesce(sum(l.{{QbOperatorSettlementLine.amount}})
               FILTER (WHERE l.{{QbOperatorSettlementLine.lineKind}} = 'GIFT' AND l.{{QbOperatorSettlementLine.noShare}}
                         AND c.{{QbOperatorSettlementLine.lineId}} IS NOT NULL), 0) AS r_gift_paid,
           coalesce(sum(l.{{QbOperatorSettlementLine.recoverGross}})
               FILTER (WHERE l.{{QbOperatorSettlementLine.lineKind}} = 'GIFT'), 0) AS r_gift_used,
           coalesce(sum(l.{{QbOperatorSettlementLine.recoverGross}} * l.{{QbOperatorSettlementLine.recoveryRate}})
               FILTER (WHERE l.{{QbOperatorSettlementLine.lineKind}} = 'GIFT'), 0) AS r_gift_due,
           coalesce(sum(l.{{QbOperatorSettlementLine.inBase}} * (1 - l.{{QbOperatorSettlementLine.shareRate}}))
               FILTER (WHERE l.{{QbOperatorSettlementLine.lineKind}} = 'GIFT' AND l.{{QbOperatorSettlementLine.noShare}}
                         AND c.{{QbOperatorSettlementLine.lineId}} IS NULL), 0) AS r_gift_share,
           coalesce(sum((l.{{QbOperatorSettlementLine.amount}} - l.{{QbOperatorSettlementLine.excluded}})
                        * (1 - l.{{QbOperatorSettlementLine.shareRate}}))
               FILTER (WHERE l.{{QbOperatorSettlementLine.lineKind}} = 'VOID'), 0) AS r_void
    FROM {{QbOperatorSettlementLine}} l
    LEFT JOIN {{QbOperatorSettlementLine}} c
        ON l.{{QbOperatorSettlementLine.lineKind}} = 'GIFT' AND c.{{QbOperatorSettlementLine.lineKind}} = 'CHARGE'
       AND c.{{QbOperatorSettlementLine.settlementId}} = l.{{QbOperatorSettlementLine.settlementId}}
       AND c.{{QbOperatorSettlementLine.chargeId}} = l.{{QbOperatorSettlementLine.chargeId}}
    GROUP BY l.{{QbOperatorSettlementLine.settlementId}}
),
column_checks AS (
    SELECT s.r_operator, s.r_month, v.r_column, v.r_expected, v.r_actual
    FROM new_settlements s
    LEFT JOIN line_sums ls ON ls.r_id = s.r_id_text
    CROSS JOIN LATERAL (VALUES
        ('platformFees', coalesce(ls.r_fees, 0), s.r_platform_fees),
        ('costExcluded', coalesce(ls.r_excluded, 0), s.r_cost_excluded),
        ('giftPaid', coalesce(ls.r_gift_paid, 0), s.r_gift_paid),
        ('giftUsed', coalesce(ls.r_gift_used, 0), s.r_gift_used),
        ('giftDue', floor(coalesce(ls.r_gift_due, 0)), s.r_gift_due),
        ('giftShareDeduction', floor(coalesce(ls.r_gift_share, 0)), s.r_gift_share_deduction),
        ('voidDeduction', floor(-coalesce(ls.r_void, 0)), s.r_void_deduction)) AS v(r_column, r_expected, r_actual)
    WHERE v.r_expected <> v.r_actual
),
missed_fees AS (
    -- A fee with no CHARGE line (and no voided invoice, as the settlement reads the fees) that two settlements of its operator
    -- should have counted. A settlement should have counted it when it was issued after the fee was charged, from its
    -- switch point on, for its month or a later one, unless it was capped and the fee is after its watermark (the
    -- fees a capped settlement left are the ones after it, in the order the settlement takes them).
    SELECT c.{{QbFeeCharge.operatorId}} AS r_operator, c.{{QbFeeCharge.period}} AS r_period,
           sum(c.{{QbFeeCharge.amount}}) AS r_amount
    FROM {{QbFeeCharge}} c
    WHERE c.{{QbFeeCharge.operatorId}} IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM {{QbOperatorSettlementLine}} l
                      WHERE l.{{QbOperatorSettlementLine.lineKind}} = 'CHARGE'
                        AND l.{{QbOperatorSettlementLine.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))
      AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
                      WHERE i.{{QbFeeInvoice.status}} = 'VOID'
                        AND ((i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))
                             OR (i.{{QbFeeInvoice.chargeId}} IS NULL
                                 AND i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                                 AND i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}}
                                 AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                                 AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} o
                                                 WHERE o.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)))))
      AND (SELECT count(*) FROM new_settlements s
           WHERE s.r_operator = c.{{QbFeeCharge.operatorId}}
             AND c.{{QbFeeCharge.chargedAt}} >= s.r_covered_from
             AND s.r_month >= c.{{QbFeeCharge.period}}
             AND s.r_issued_at > c.{{QbFeeCharge.chargedAt}}
             AND NOT (s.r_capped AND (s.r_mark_at IS NULL
                      OR c.{{QbFeeCharge.chargedAt}} > s.r_mark_at
                      OR (c.{{QbFeeCharge.chargedAt}} = s.r_mark_at
                          AND CAST(c.{{QbFeeCharge.chargeId}} AS text) COLLATE "C" > s.r_mark_id COLLATE "C")))) >= 2
    GROUP BY c.{{QbFeeCharge.operatorId}}, c.{{QbFeeCharge.period}}
),
credit_parts AS (
    -- The parts of payments made with granted credit from the earliest switch point on.
    SELECT p.{{QbFeePayment.paymentId}} AS payment_id, p.{{QbFeePayment.targetEntity}} AS target_entity,
           p.{{QbFeePayment.targetId}} AS target_id, p.{{QbFeePayment.amount}} AS amount,
           p.{{QbFeePayment.paidAt}} AS paid_at
    FROM {{QbFeePayment}} p
    WHERE p.{{QbFeePayment.subAccount}} = 'FEE_CREDIT'
      AND p.{{QbFeePayment.paidAt}} >= (SELECT min(n.r_covered_from) FROM new_settlements n)
),
resolved AS (
    -- Each part resolved to the fee(s) it paid, as qb.system.operator-settlement-gifts does: a fee directly, an invoice's
    -- fees, a credit note by its fee.
    SELECT g.payment_id, c.{{QbFeeCharge.chargeId}} AS charge_uuid, g.amount, g.paid_at
    FROM credit_parts g
    JOIN {{QbFeeCharge}} c ON c.{{QbFeeCharge.chargeId}} = CAST(g.target_id AS uuid)
    WHERE g.target_entity = 'QbFeeCharge'
    UNION ALL
    SELECT g.payment_id, c.{{QbFeeCharge.chargeId}}, g.amount, g.paid_at
    FROM credit_parts g
    JOIN {{QbFeeInvoice}} i ON i.{{QbFeeInvoice.invoiceId}} = g.target_id
    JOIN {{QbFeeCharge}} c ON c.{{QbFeeCharge.chargeId}} = CAST(i.{{QbFeeInvoice.chargeId}} AS uuid)
    WHERE g.target_entity = 'QbFeeInvoice' AND i.{{QbFeeInvoice.chargeId}} IS NOT NULL
    UNION ALL
    SELECT g.payment_id, c.{{QbFeeCharge.chargeId}}, g.amount, g.paid_at
    FROM credit_parts g
    JOIN {{QbFeeInvoice}} i ON i.{{QbFeeInvoice.invoiceId}} = g.target_id
    JOIN {{QbFeeCharge}} c ON c.{{QbFeeCharge.sponsorId}} = i.{{QbFeeInvoice.sponsorId}}
                           AND c.{{QbFeeCharge.period}} = i.{{QbFeeInvoice.period}}
                           AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                           AND c.{{QbFeeCharge.chargedAt}} <= i.{{QbFeeInvoice.issuedAt}}
    WHERE g.target_entity = 'QbFeeInvoice' AND i.{{QbFeeInvoice.chargeId}} IS NULL
      AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} o
                      WHERE o.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))
    UNION ALL
    SELECT g.payment_id, c.{{QbFeeCharge.chargeId}}, g.amount, g.paid_at
    FROM credit_parts g
    JOIN {{QbFeeCreditNote}} n ON n.{{QbFeeCreditNote.creditNoteId}} = CAST(g.target_id AS uuid)
    JOIN {{QbFeeCharge}} c ON c.{{QbFeeCharge.chargeId}} = CAST(n.{{QbFeeCreditNote.chargeId}} AS uuid)
    WHERE g.target_entity = 'QbFeeCreditNote'
),
missed_parts AS (
    -- A part on a fee a settlement counted (its CHARGE line) with no GIFT line for the pair, that two settlements of the
    -- operator that could have written it did not: those since the one that counted the fee, issued after the payment;
    -- a capped one did not have to for the fees counted before it (the credit used on the fees counted is never cut).
    SELECT x.r_operator, x.r_month, sum(abs(x.r_amount)) AS r_amount
    FROM (
        SELECT DISTINCT c.{{QbFeeCharge.operatorId}} AS r_operator, r.payment_id, r.amount AS r_amount,
               to_char(r.paid_at AT TIME ZONE coalesce(CAST(:zone AS text), 'UTC'), 'YYYY-MM') AS r_month
        FROM resolved r
        JOIN {{QbFeeCharge}} c ON c.{{QbFeeCharge.chargeId}} = r.charge_uuid
        JOIN {{QbOperatorSettlementLine}} cl ON cl.{{QbOperatorSettlementLine.lineKind}} = 'CHARGE'
                                            AND cl.{{QbOperatorSettlementLine.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)
        JOIN new_settlements s0 ON s0.r_id_text = cl.{{QbOperatorSettlementLine.settlementId}}
        WHERE c.{{QbFeeCharge.operatorId}} IS NOT NULL
          AND r.paid_at >= s0.r_covered_from
          AND NOT EXISTS (SELECT 1 FROM {{QbOperatorSettlementLine}} g
                          WHERE g.{{QbOperatorSettlementLine.lineKind}} = 'GIFT'
                            AND g.{{QbOperatorSettlementLine.sourceId}} = CAST(r.payment_id AS text)
                            AND g.{{QbOperatorSettlementLine.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))
          AND (SELECT count(*) FROM new_settlements s
               WHERE s.r_operator = c.{{QbFeeCharge.operatorId}}
                 AND s.r_issued_at >= s0.r_issued_at AND s.r_issued_at > r.paid_at
                 AND (NOT s.r_capped OR s.r_id = s0.r_id)) >= 2) x
    GROUP BY x.r_operator, x.r_month
),
recovered_rows AS (
    SELECT s.r_operator, sum(s.r_gift_recovered) AS r_sum FROM new_settlements s GROUP BY s.r_operator
),
recovered_books AS (
    SELECT coalesce(s.r_operator, '-') AS r_operator,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_net
    FROM {{LedgerTransaction}} t
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    LEFT JOIN new_settlements s ON s.r_id_text = t.{{LedgerTransaction.sourceId}}
    WHERE t.{{LedgerTransaction.sourceEntity}} = 'QbOperatorSettlement'
      AND a.{{LedgerAccount.accountCode}} = '5140'
    GROUP BY coalesce(s.r_operator, '-')
),
due_months AS (
    -- The months before the one :at falls in, from the month after the operator's last settlement of the old kind (or
    -- the one it was created in), as qb.system.operator-settlements-due has them.
    SELECT o.{{QbOperator.operatorId}} AS r_operator, m.start_of_month AS r_local_start
    FROM {{QbOperator}} o
    CROSS JOIN LATERAL (
        SELECT COALESCE((SELECT CAST(max(os.{{QbOperatorSettlement.month}}) || '-01' AS timestamp) + interval '1 month'
                         FROM {{QbOperatorSettlement}} os
                         WHERE os.{{QbOperatorSettlement.operatorId}} = o.{{QbOperator.operatorId}}
                           AND os.{{QbOperatorSettlement.coveredFrom}} IS NULL),
                        date_trunc('month', o.{{QbOperator.createdAt}} AT TIME ZONE CAST(:zone AS text))) AS lower_month
    ) lo
    CROSS JOIN LATERAL generate_series(lo.lower_month,
        date_trunc('month', CAST(:at AS timestamptz) AT TIME ZONE CAST(:zone AS text)) - interval '1 month',
        interval '1 month') AS m(start_of_month)
    WHERE CAST(:at AS timestamptz) IS NOT NULL AND CAST(:zone AS text) IS NOT NULL
      AND CAST(:waitingHours AS int) IS NOT NULL
),
unsettled AS (
    SELECT d.r_operator, to_char(d.r_local_start, 'YYYY-MM') AS r_month
    FROM due_months d
    WHERE NOT EXISTS (SELECT 1 FROM {{QbOperatorSettlement}} s
                      WHERE s.{{QbOperatorSettlement.operatorId}} = d.r_operator
                        AND s.{{QbOperatorSettlement.month}} = to_char(d.r_local_start, 'YYYY-MM'))
),
waiting AS (
    SELECT h.r_operator, h.r_month, h.r_behind,
           (CAST(h.r_month || '-01' AS timestamp) AT TIME ZONE CAST(:zone AS text)) AS r_from,
           ((CAST(h.r_month || '-01' AS timestamp) + interval '1 month') AT TIME ZONE CAST(:zone AS text)) AS r_to
    FROM (SELECT u.r_operator, min(u.r_month) AS r_month, count(*) AS r_behind
          FROM unsettled u GROUP BY u.r_operator) h
    WHERE ((CAST(h.r_month || '-01' AS timestamp) + interval '1 month') AT TIME ZONE CAST(:zone AS text))
          + make_interval(hours => CAST(:waitingHours AS int)) < CAST(:at AS timestamptz)
),
carried AS (
    SELECT o.{{QbOperator.operatorId}} AS r_operator, s.r_gift_carried_out AS r_carried
    FROM {{QbOperator}} o
    JOIN new_settlements s ON s.r_operator = o.{{QbOperator.operatorId}}
    WHERE o.{{QbOperator.status}} <> 'ACTIVE' AND s.r_gift_carried_out > 0
      AND s.r_month = (SELECT max(n.r_month) FROM new_settlements n WHERE n.r_operator = o.{{QbOperator.operatorId}})
)
SELECT CAST('OPERATOR_SETTLEMENT_WAITING' AS varchar(30)) AS checkCode, CAST(w.r_operator AS varchar(128)) AS sponsorId,
       CAST(rtrim(w.r_month || ':' || concat_ws(',',
           CASE WHEN EXISTS (
               SELECT 1
               FROM {{QbPublicationCard}} pc
               JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = pc.{{QbPublicationCard.publicationId}}
               WHERE pc.{{QbPublicationCard.operatorId}} = w.r_operator
                 AND p.{{QbPublication.billingOperatorId}} = w.r_operator
                 AND p.{{QbPublication.distributionUnit}} IS NOT NULL
                 AND p.{{QbPublication.publisherKind}} = 'SPONSOR'
                 AND greatest(pc.{{QbPublicationCard.startAt}}, p.{{QbPublication.approvedAt}}) < w.r_to
                 AND (pc.{{QbPublicationCard.endedAt}} IS NULL OR pc.{{QbPublicationCard.endedAt}} >= w.r_from)
                 AND NOT EXISTS (SELECT 1 FROM {{QbDistributionSettlement}} ds
                                 WHERE ds.{{QbDistributionSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
                                   AND (ds.{{QbDistributionSettlement.final}} OR ds.{{QbDistributionSettlement.period}} = w.r_month)))
               THEN 'DISTRIBUTION' END,
           CASE WHEN EXISTS (
               SELECT 1
               FROM {{QbFeeCharge}} c
               WHERE c.{{QbFeeCharge.operatorId}} = w.r_operator
                 AND c.{{QbFeeCharge.period}} = w.r_month
                 AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                 AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
                                 WHERE i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                                   AND ((i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}} AND i.{{QbFeeInvoice.chargeId}} IS NULL)
                                        OR i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))))
               THEN 'INVOICE' END), ':') AS varchar(128)) AS reference,
       CAST(0 AS numeric) AS expected, CAST(w.r_behind AS numeric) AS actual
FROM waiting w
UNION ALL
SELECT CAST('OPERATOR_SETTLEMENT_COVERAGE' AS varchar(30)), CAST(k.r_operator AS varchar(128)),
       CAST(k.r_month || ':' || k.r_column AS varchar(128)), k.r_expected, k.r_actual
FROM column_checks k
UNION ALL
SELECT CAST('OPERATOR_SETTLEMENT_COVERAGE' AS varchar(30)), CAST(f.r_operator AS varchar(128)),
       CAST('fee:' || f.r_period AS varchar(128)), f.r_amount, CAST(0 AS numeric)
FROM missed_fees f
UNION ALL
SELECT CAST('OPERATOR_SETTLEMENT_COVERAGE' AS varchar(30)), CAST(q.r_operator AS varchar(128)),
       CAST('gift:' || q.r_month AS varchar(128)), q.r_amount, CAST(0 AS numeric)
FROM missed_parts q
UNION ALL
SELECT CAST('GIFT_RECOVERY' AS varchar(30)), CAST(coalesce(rr.r_operator, rb.r_operator) AS varchar(128)),
       CAST('recovered' AS varchar(128)), coalesce(rr.r_sum, 0), coalesce(rb.r_net, 0)
FROM recovered_rows rr
FULL OUTER JOIN recovered_books rb ON rb.r_operator = rr.r_operator
WHERE coalesce(rr.r_sum, 0) <> coalesce(rb.r_net, 0)
UNION ALL
SELECT CAST('GIFT_RECOVERY' AS varchar(30)), CAST(s.r_operator AS varchar(128)), CAST(s.r_month AS varchar(128)),
       s.r_gift_due, s.r_gift_recovered + s.r_gift_carried_out - s.r_gift_carried_in
FROM new_settlements s
WHERE s.r_gift_due <> s.r_gift_recovered + s.r_gift_carried_out - s.r_gift_carried_in
UNION ALL
SELECT CAST('OPERATOR_CHARGE_NO_OPERATOR' AS varchar(30)), CAST(c.{{QbFeeCharge.sponsorId}} AS varchar(128)),
       CAST(c.{{QbFeeCharge.period}} AS varchar(128)), CAST(0 AS numeric), sum(c.{{QbFeeCharge.amount}})
FROM {{QbFeeCharge}} c
WHERE c.{{QbFeeCharge.operatorId}} IS NULL
GROUP BY c.{{QbFeeCharge.sponsorId}}, c.{{QbFeeCharge.period}}
UNION ALL
SELECT CAST('OPERATOR_GIFT_CARRY_OPEN' AS varchar(30)), CAST(k.r_operator AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), k.r_carried
FROM carried k
