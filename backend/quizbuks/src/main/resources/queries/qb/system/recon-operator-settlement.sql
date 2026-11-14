/*---
id: qb.system.recon-operator-settlement
description: Whether the operators' settlements agree with their lines and with the ledger (F-M12 c) - the operators still without a settlement for a month and the step they wait for, what a settlement counted against its own columns and the fees and parts of payments made with granted credit that nothing counted, the granted credit taken back against 5140 and month by month, the fees without an operator, and (a note) the suspended operators that still carry credit to take back
entities: [QbOperator, QbOperatorSettlement, QbOperatorSettlementLine, QbFeeCharge, QbFeeInvoice, QbFeePayment, QbFeeCreditNote, QbPublication, QbPublicationCard, QbDistributionSettlement, QbExposureSettlement, LedgerEntry, LedgerAccount, LedgerTransaction]
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
  QbExposureSettlement: urn:jabiz:dataset:default:QbExposureSettlement
params:
  at: { like: QbOperatorSettlement.issuedAt, description: "The run's time: the months before the one it falls in are due (none: the waiting ones are not checked)" }
  zone: { kind: { type: text, maxLength: 64 }, description: "The fiscal year's zone, whose months the settlements are of (none: the waiting ones are not checked)" }
  readinessDays: { kind: { type: numeric, precision: 2, scale: 0 }, description: "F-M12 b: qb.operator.settlement.readiness-max-days (none: counted as 0, from the month's end)" }
  since: { like: QbOperatorSettlement.issuedAt, description: "The window of the work: settlements issued, fees charged and payments made from this time on are checked (none: all history). The caller gives the last clean run's time less three months" }
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
-- Work is bounded by :since (the caller gives the last clean run's time less three months, none: all of the history): the
--   settlements issued from then on are checked against their lines, a fee or a part of a payment nobody counted is reported
--   when the later of the two settlements that should have counted it is issued from then on, and an operator's credit taken
--   back is compared over all of its history when it has a settlement or a posting inside it. What is counted over is not
--   narrowed: the settlements, the fees and the payments nobody counted (either can stay uncounted for longer than the
--   window), the settlement that counted a fee.
--   A difference stays in the run until it is put right, so the window does not let one age out. The waiting check does not
--   read it.
-- OPERATOR_SETTLEMENT_WAITING (a difference on purpose: a month still unsettled this long after its deadline must hold the
--   journal export back): an operator's earliest month without a settlement (head of the line: the months after it wait for
--   it, they are not listed), as the daily job's items take it (qb.system.operator-settlements-due), is reported when the run
--   is past start + :waitingHours, start = min(max(the month's end, readyAt), the month's end + :readinessDays days) in the
--   zone: readyAt is when the last step it needed was satisfied (the rows' own times); while a step is pending, start is
--   the deadline; nothing needed, readyAt is the month's end; :readinessDays not given counts as 0. The reference is "<month>:<steps>": the steps of the month that are
--   not done yet, the branches of qb.system.operator-settlement-readiness (DISTRIBUTION, INVOICE, EXPOSURE, written the same: OperatorReadinessTemplateTest compares them; the month alone when
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
-- GIFT_RECOVERY, reference "<month>:carried": the credit carried in is what the month before carried out.
-- GIFT_RECOVERY, reference "recovered": what the settlements say they took back against the net credit to 5140 of the
--   postings made for them (5140, not 2140: a refund that paid the cost carried posts that part to 4110, §4); reference
--   "<month>": what was due of the month against what was taken back and carried out less what was carried in.
-- OPERATOR_CHARGE_NO_OPERATOR: by sponsor and period, fees with no operator (no settlement counts them), from the
--   earliest switch point of the operators.
-- OPERATOR_GIFT_CARRY_OPEN (a note): a suspended operator whose latest settlement carries credit to take back.
WITH all_new_settlements AS (
    -- Every settlement of the new kind, whatever its age: what is counted over (the two settlements that could have written a
    -- line) and where the switch points are read is the whole of it; only the work on the settlements' own rows is windowed.
    SELECT s.{{QbOperatorSettlement.settlementId}} AS r_id,
           CAST(s.{{QbOperatorSettlement.settlementId}} AS text) AS r_id_text,
           s.{{QbOperatorSettlement.operatorId}} AS r_operator,
           s.{{QbOperatorSettlement.month}} AS r_month,
           s.{{QbOperatorSettlement.issuedAt}} AS r_issued_at,
           s.{{QbOperatorSettlement.coveredFrom}} AS r_covered_from,
           s.{{QbOperatorSettlement.capped}} AS r_capped,
           s.{{QbOperatorSettlement.watermarkChargedAt}} AS r_mark_at,
           s.{{QbOperatorSettlement.watermarkChargeId}} AS r_mark_id
    FROM {{QbOperatorSettlement}} s
    WHERE s.{{QbOperatorSettlement.coveredFrom}} IS NOT NULL
),
new_settlements AS (
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
      AND s.{{QbOperatorSettlement.issuedAt}} >= coalesce(CAST(:since AS timestamptz), '-infinity')
),
switch_points AS (
    SELECT n.r_operator, min(n.r_covered_from) AS r_at FROM all_new_settlements n GROUP BY n.r_operator
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
    FROM new_settlements ns
    JOIN {{QbOperatorSettlementLine}} l ON l.{{QbOperatorSettlementLine.settlementId}} = ns.r_id_text
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
uncounted_fees AS MATERIALIZED (

    -- A fee with no CHARGE line (and no voided invoice, as the settlement reads the fees) that two settlements of its operator
    -- should have counted. A settlement should have counted it when it was issued after the fee was charged, from its
    -- switch point on, for its month or a later one, unless it was capped and the fee is after its watermark (the
    -- fees a capped settlement left are the ones after it, in the order the settlement takes them).
    SELECT c.{{QbFeeCharge.operatorId}} AS r_operator, c.{{QbFeeCharge.period}} AS r_period,
           c.{{QbFeeCharge.amount}} AS r_amount, c.{{QbFeeCharge.chargedAt}} AS r_charged_at,
           CAST(c.{{QbFeeCharge.chargeId}} AS text) AS r_charge_id
    FROM {{QbFeeCharge}} c
    JOIN switch_points sp ON sp.r_operator = c.{{QbFeeCharge.operatorId}}
                         AND c.{{QbFeeCharge.chargedAt}} >= sp.r_at
    -- Not narrowed by when it was charged: a fee can stay uncounted for more than the window (a capped settlement, an operator
    -- long behind); the switch point and the lines already leave only the fees nobody counted. The window is of the
    -- settlements below: one of the two that should have counted it is issued inside it.
    -- A CHARGE line's source is its fee (the table's check, plan M4), so it is looked for by the whole of the unique
    -- index (line_kind, source_id, charge_id). Written as a scalar subquery, not NOT EXISTS, on purpose: an anti join is
    -- planned from the table's statistics, and right after a bulk load (none yet) the planner takes the CHARGE lines
    -- for a handful and compares every fee with all of them (seconds at 15,000); the subquery is one index probe a
    -- fee whatever the planner knows (the check's budget, OperatorSettlementReconBudgetIT).
    WHERE (SELECT 1 FROM {{QbOperatorSettlementLine}} l
           WHERE l.{{QbOperatorSettlementLine.lineKind}} = 'CHARGE'
             AND l.{{QbOperatorSettlementLine.sourceId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)
             AND l.{{QbOperatorSettlementLine.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)
           LIMIT 1) IS NULL
      AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
                      WHERE i.{{QbFeeInvoice.status}} = 'VOID'
                        AND ((i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))
                             OR (i.{{QbFeeInvoice.chargeId}} IS NULL
                                 AND i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                                 AND i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}}
                                 AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                                 AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} o
                                                 WHERE o.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)))))
),
missed_fees AS (
    SELECT u.r_operator, u.r_period, sum(u.r_amount) AS r_amount
    FROM uncounted_fees u
    CROSS JOIN LATERAL (
        SELECT count(*) AS r_n, max(s.r_issued_at) AS r_last
        FROM all_new_settlements s
        WHERE s.r_operator = u.r_operator
          AND u.r_charged_at >= s.r_covered_from
          AND s.r_month >= u.r_period
          AND s.r_issued_at > u.r_charged_at
          AND NOT (s.r_capped AND (s.r_mark_at IS NULL
                   OR u.r_charged_at > s.r_mark_at
                   OR (u.r_charged_at = s.r_mark_at
                       AND u.r_charge_id COLLATE "C" > s.r_mark_id COLLATE "C")))) q
    -- Two settlements should have counted it, and the later of them is inside the window (an older one was checked by
    -- the run that found it clean).
    WHERE q.r_n >= 2 AND q.r_last >= coalesce(CAST(:since AS timestamptz), '-infinity')
    GROUP BY u.r_operator, u.r_period
),
credit_parts AS (
    -- The parts of payments made with granted credit from the earliest switch point on. Not narrowed by when they were paid
    -- (as the fees are not): a part can wait for its second settlement longer than the window (one issued more than three
    -- months after the payment); the window is of the later of the two settlements (missed_parts).
    SELECT p.{{QbFeePayment.paymentId}} AS payment_id, p.{{QbFeePayment.targetEntity}} AS target_entity,
           p.{{QbFeePayment.targetId}} AS target_id, p.{{QbFeePayment.amount}} AS amount,
           p.{{QbFeePayment.paidAt}} AS paid_at
    FROM {{QbFeePayment}} p
    WHERE p.{{QbFeePayment.subAccount}} = 'FEE_CREDIT'
      AND p.{{QbFeePayment.paidAt}} >= (SELECT min(n.r_at) FROM switch_points n)
      -- A part paid on one fee is done once its GIFT line is written, as nearly all are: left out here (an anti-join on the
      -- unique index: a hash with statistics, a probe a part without), before it is resolved and its fee's CHARGE line looked up (#701 review M3: 100,000 such parts took
      -- the check past its budget). A part of an invoice or a credit note may pay several fees, counted by different
      -- settlements: such a part on an invoice is left out when it has as many GIFT lines as the invoice has fees (one
      -- line a fee: the unique index), its fees counted as resolved below (#701 re-review M-1: 24,000 of them on invoices
      -- of five fees took the check past its timeout). The rest go on and are checked fee by fee in unmatched_parts.
      -- What is left unbounded (re-review L-5): the parts on credit notes, and those on invoices not yet fully written,
      -- are resolved on every run.
      AND NOT EXISTS (SELECT 1 FROM {{QbOperatorSettlementLine}} g
                      WHERE p.{{QbFeePayment.targetEntity}} = 'QbFeeCharge'
                        AND g.{{QbOperatorSettlementLine.lineKind}} = 'GIFT'
                        AND g.{{QbOperatorSettlementLine.sourceId}} = CAST(p.{{QbFeePayment.paymentId}} AS text)
                        AND g.{{QbOperatorSettlementLine.chargeId}} = p.{{QbFeePayment.targetId}})
      AND NOT (p.{{QbFeePayment.targetEntity}} = 'QbFeeInvoice'
               AND (SELECT count(*) FROM {{QbOperatorSettlementLine}} g
                    WHERE g.{{QbOperatorSettlementLine.lineKind}} = 'GIFT'
                      AND g.{{QbOperatorSettlementLine.sourceId}} = CAST(p.{{QbFeePayment.paymentId}} AS text))
                   >= (SELECT CASE WHEN i.{{QbFeeInvoice.chargeId}} IS NOT NULL THEN 1 ELSE
                              (SELECT count(*) FROM {{QbFeeCharge}} c
                               WHERE c.{{QbFeeCharge.sponsorId}} = i.{{QbFeeInvoice.sponsorId}}
                                 AND c.{{QbFeeCharge.period}} = i.{{QbFeeInvoice.period}}
                                 AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                                 AND c.{{QbFeeCharge.chargedAt}} <= i.{{QbFeeInvoice.issuedAt}}
                                 AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} o
                                                 WHERE o.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)))
                              END
                       FROM {{QbFeeInvoice}} i WHERE i.{{QbFeeInvoice.invoiceId}} = p.{{QbFeePayment.targetId}}))
),
resolved AS MATERIALIZED (
    -- Each part resolved to the fee(s) it paid, as qb.system.operator-settlement-gifts does: a fee directly, an invoice's
    -- fees, a credit note by its fee. Worked out once (MATERIALIZED): inlined, a planner with no statistics yet takes
    -- the parts for one row and works them out again for every CHARGE line (tens of seconds at 6,000 lines).
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
unmatched_parts AS MATERIALIZED (
    -- A part on a fee a settlement counted (its CHARGE line) with no GIFT line for the pair (read before anything is
    -- counted: it is rare, and what is counted below is only these).
    SELECT c.{{QbFeeCharge.operatorId}} AS r_operator, r.payment_id, r.amount AS r_amount, r.paid_at AS r_paid_at,
           s0.r_id AS r_s0_id, s0.r_issued_at AS r_s0_issued_at
    FROM resolved r
    JOIN {{QbFeeCharge}} c ON c.{{QbFeeCharge.chargeId}} = r.charge_uuid
    -- The fee's CHARGE line (one at most: the unique index), one probe of the whole index a part, by its source as
    -- uncounted_fees. LATERAL with LIMIT on purpose: joined plainly, a planner with no statistics yet takes the CHARGE
    -- lines for one and crosses all of them with the parts (seconds at 6,000 lines).
    CROSS JOIN LATERAL (SELECT cl.{{QbOperatorSettlementLine.settlementId}} AS r_settlement
                        FROM {{QbOperatorSettlementLine}} cl
                        WHERE cl.{{QbOperatorSettlementLine.lineKind}} = 'CHARGE'
                          AND cl.{{QbOperatorSettlementLine.sourceId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)
                          AND cl.{{QbOperatorSettlementLine.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)
                        LIMIT 1) cl
    JOIN all_new_settlements s0 ON s0.r_id_text = cl.r_settlement
    WHERE c.{{QbFeeCharge.operatorId}} IS NOT NULL
      AND r.paid_at >= s0.r_covered_from
      -- (one probe of the unique index a part, as uncounted_fees, for the same reason)
      AND (SELECT 1 FROM {{QbOperatorSettlementLine}} g
           WHERE g.{{QbOperatorSettlementLine.lineKind}} = 'GIFT'
             AND g.{{QbOperatorSettlementLine.sourceId}} = CAST(r.payment_id AS text)
             AND g.{{QbOperatorSettlementLine.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)
           LIMIT 1) IS NULL
),
missed_parts AS (
    -- ... that at least two settlements of the operator that could have written it did not. They are the settlements
    -- issued after the payment, from the one that counted the fee on (that one included); a capped one other than that
    -- one does not count (it did not have to write the parts of fees counted before it, though the credit used on the
    -- fees it counted is never cut). The payments and the settlements are any (a fee counted long ago may be paid with
    -- credit only now, a part may wait long for its second settlement); reported while the latest of those settlements
    -- is inside the window (:since): a run whose window holds none of them was preceded by one whose window held the
    -- latest, which reported it then.
    SELECT x.r_operator, x.r_month, sum(abs(x.r_amount)) AS r_amount
    FROM (
        SELECT DISTINCT u.r_operator, u.payment_id, u.r_amount,
               to_char(u.r_paid_at AT TIME ZONE coalesce(CAST(:zone AS text), 'UTC'), 'YYYY-MM') AS r_month
        FROM unmatched_parts u
        CROSS JOIN LATERAL (
            SELECT count(*) AS r_n, max(s.r_issued_at) AS r_last
            FROM all_new_settlements s
            WHERE s.r_operator = u.r_operator
              AND s.r_issued_at >= u.r_s0_issued_at AND s.r_issued_at > u.r_paid_at
              AND (NOT s.r_capped OR s.r_id = u.r_s0_id)) q
        WHERE q.r_n >= 2 AND q.r_last >= coalesce(CAST(:since AS timestamptz), '-infinity')) x
    GROUP BY x.r_operator, x.r_month
),
recent_operators AS (
    -- The operators with a settlement issued, or a posting made for one (a reversal keeps the source of what it reverses),
    -- inside the window: what they took back is compared over all of their history.
    SELECT n.r_operator FROM new_settlements n
    UNION
    SELECT st.{{QbOperatorSettlement.operatorId}}
    FROM {{LedgerTransaction}} t
    JOIN {{QbOperatorSettlement}} st ON CAST(st.{{QbOperatorSettlement.settlementId}} AS text) = t.{{LedgerTransaction.sourceId}}
    WHERE t.{{LedgerTransaction.sourceEntity}} = 'QbOperatorSettlement'
      AND t.{{LedgerTransaction.createdTime}} >= coalesce(CAST(:since AS timestamptz), '-infinity')
),
recovered_rows AS (
    SELECT s.{{QbOperatorSettlement.operatorId}} AS r_operator, sum(s.{{QbOperatorSettlement.giftRecovered}}) AS r_sum
    FROM {{QbOperatorSettlement}} s
    WHERE s.{{QbOperatorSettlement.coveredFrom}} IS NOT NULL
      AND s.{{QbOperatorSettlement.operatorId}} IN (SELECT r.r_operator FROM recent_operators r)
    GROUP BY s.{{QbOperatorSettlement.operatorId}}
),
recovered_books AS (
    SELECT coalesce(s.{{QbOperatorSettlement.operatorId}}, '-') AS r_operator,
           sum(CASE WHEN e.{{LedgerEntry.direction}} = 'CREDIT' THEN e.{{LedgerEntry.amount}}
               ELSE -e.{{LedgerEntry.amount}} END) AS r_net
    FROM {{LedgerTransaction}} t
    JOIN {{LedgerEntry}} e ON e.{{LedgerEntry.transactionId}} = t.{{LedgerTransaction.transactionId}}
    JOIN {{LedgerAccount}} a ON a.{{LedgerAccount.accountId}} = e.{{LedgerEntry.accountId}}
    LEFT JOIN {{QbOperatorSettlement}} s ON CAST(s.{{QbOperatorSettlement.settlementId}} AS text) = t.{{LedgerTransaction.sourceId}}
    WHERE t.{{LedgerTransaction.sourceEntity}} = 'QbOperatorSettlement'
      AND a.{{LedgerAccount.accountCode}} = '5140'
      -- A posting of no settlement is always read; of one, when its operator has something inside the window.
      AND (s.{{QbOperatorSettlement.settlementId}} IS NULL
           OR s.{{QbOperatorSettlement.operatorId}} IN (SELECT r.r_operator FROM recent_operators r))
    GROUP BY coalesce(s.{{QbOperatorSettlement.operatorId}}, '-')
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
behind AS (
    SELECT h.r_operator, h.r_month, h.r_behind,
           (CAST(h.r_month || '-01' AS timestamp) AT TIME ZONE CAST(:zone AS text)) AS r_from,
           ((CAST(h.r_month || '-01' AS timestamp) + interval '1 month') AT TIME ZONE CAST(:zone AS text)) AS r_to,
           (((CAST(h.r_month || '-01' AS timestamp) + interval '1 month') + make_interval(days => coalesce(CAST(:readinessDays AS int), 0)))
               AT TIME ZONE CAST(:zone AS text)) AS r_deadline
    FROM (SELECT u.r_operator, min(u.r_month) AS r_month, count(*) AS r_behind
          FROM unsettled u GROUP BY u.r_operator) h
    -- The earliest a month can be reported is the waiting hours after its end (a step it waits for only puts that later):
    -- cut here, before the steps and the ready time are worked out, so that the first days of a month read nothing.
    WHERE ((CAST(h.r_month || '-01' AS timestamp) + interval '1 month') AT TIME ZONE CAST(:zone AS text))
          + make_interval(hours => CAST(:waitingHours AS int)) < CAST(:at AS timestamptz)
),
waiting AS (
    -- The steps it waits for, and when it is reported (General Controller's rule, 2026-11-13): a month that waits for a
    -- step is allowed the readiness wait (the settlement itself waits that long: qb.operator.settlement.readiness-max-days
    -- after the month's end, in the zone; a number not published counts as 0, so the month's end) and is reported the
    -- waiting hours after that; a month that waits for nothing (the job did not run) is reported the waiting hours after
    -- the month's end, inside the readiness wait too.
    SELECT w0.r_operator, w0.r_month, w0.r_behind, w0.r_steps
    FROM (
        SELECT b.*, concat_ws(',',
           CASE WHEN EXISTS (
               SELECT 1
               FROM {{QbPublicationCard}} pc
               JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = pc.{{QbPublicationCard.publicationId}}
               WHERE pc.{{QbPublicationCard.operatorId}} = b.r_operator
                 AND p.{{QbPublication.billingOperatorId}} = b.r_operator
                 AND p.{{QbPublication.distributionUnit}} IS NOT NULL
                 AND p.{{QbPublication.publisherKind}} = 'SPONSOR'
                 AND greatest(pc.{{QbPublicationCard.startAt}}, p.{{QbPublication.approvedAt}}) < b.r_to
                 AND (pc.{{QbPublicationCard.endedAt}} IS NULL OR pc.{{QbPublicationCard.endedAt}} >= b.r_from)
                 AND NOT EXISTS (SELECT 1 FROM {{QbDistributionSettlement}} s
                                 WHERE s.{{QbDistributionSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
                                   AND (s.{{QbDistributionSettlement.final}} OR s.{{QbDistributionSettlement.period}} = b.r_month)))
               THEN 'DISTRIBUTION' END,
           CASE WHEN EXISTS (
               SELECT 1
               FROM {{QbFeeCharge}} c
               WHERE c.{{QbFeeCharge.operatorId}} = b.r_operator
                 AND c.{{QbFeeCharge.period}} = b.r_month
                 AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                 AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
                                 WHERE i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                                   AND ((i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}} AND i.{{QbFeeInvoice.chargeId}} IS NULL)
                                        OR i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))))
               THEN 'INVOICE' END,
           CASE WHEN EXISTS (
               SELECT 1
               FROM {{QbPublicationCard}} pc
               JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = pc.{{QbPublicationCard.publicationId}}
               WHERE pc.{{QbPublicationCard.operatorId}} = b.r_operator
                 AND p.{{QbPublication.billingOperatorId}} = b.r_operator
                 AND p.{{QbPublication.exposureUnit}} IS NOT NULL
                 AND p.{{QbPublication.exposurePurchased}} IS NOT NULL
                 AND p.{{QbPublication.publisherKind}} = 'SPONSOR'
                 AND greatest(pc.{{QbPublicationCard.startAt}}, p.{{QbPublication.approvedAt}}) < b.r_to
                 AND (pc.{{QbPublicationCard.endedAt}} IS NULL OR pc.{{QbPublicationCard.endedAt}} >= b.r_from)
                 AND NOT EXISTS (SELECT 1 FROM {{QbExposureSettlement}} s
                                 WHERE s.{{QbExposureSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
                                   AND (s.{{QbExposureSettlement.final}} OR s.{{QbExposureSettlement.period}} = b.r_month)))
               THEN 'EXPOSURE' END) AS r_steps,
               -- readyAt: when the last step it needed was satisfied, by the rows' own times (settledAt of the month's or the
               -- final distribution / exposure row of each publication, issuedAt of the invoice of each postpaid fee); none
               -- when nothing was needed.
               greatest(
                   (SELECT max(t.r_at) FROM (
                       SELECT (SELECT min(s.{{QbDistributionSettlement.settledAt}}) FROM {{QbDistributionSettlement}} s
                               WHERE s.{{QbDistributionSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
                                 AND (s.{{QbDistributionSettlement.final}} OR s.{{QbDistributionSettlement.period}} = b.r_month)) AS r_at
                       FROM {{QbPublicationCard}} pc
                       JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = pc.{{QbPublicationCard.publicationId}}
                       WHERE pc.{{QbPublicationCard.operatorId}} = b.r_operator
                         AND p.{{QbPublication.billingOperatorId}} = b.r_operator
                         AND p.{{QbPublication.distributionUnit}} IS NOT NULL
                         AND p.{{QbPublication.publisherKind}} = 'SPONSOR'
                         AND greatest(pc.{{QbPublicationCard.startAt}}, p.{{QbPublication.approvedAt}}) < b.r_to
                         AND (pc.{{QbPublicationCard.endedAt}} IS NULL OR pc.{{QbPublicationCard.endedAt}} >= b.r_from)) t),
                   (SELECT max(t.r_at) FROM (
                       SELECT (SELECT min(i.{{QbFeeInvoice.issuedAt}}) FROM {{QbFeeInvoice}} i
                               WHERE i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                                 AND ((i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}} AND i.{{QbFeeInvoice.chargeId}} IS NULL)
                                      OR i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))) AS r_at
                       FROM {{QbFeeCharge}} c
                       WHERE c.{{QbFeeCharge.operatorId}} = b.r_operator
                         AND c.{{QbFeeCharge.period}} = b.r_month
                         AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID') t),
                   (SELECT max(t.r_at) FROM (
                       SELECT (SELECT min(s.{{QbExposureSettlement.settledAt}}) FROM {{QbExposureSettlement}} s
                               WHERE s.{{QbExposureSettlement.publicationId}} = CAST(p.{{QbPublication.publicationId}} AS text)
                                 AND (s.{{QbExposureSettlement.final}} OR s.{{QbExposureSettlement.period}} = b.r_month)) AS r_at
                       FROM {{QbPublicationCard}} pc
                       JOIN {{QbPublication}} p ON p.{{QbPublication.publicationId}} = pc.{{QbPublicationCard.publicationId}}
                       WHERE pc.{{QbPublicationCard.operatorId}} = b.r_operator
                         AND p.{{QbPublication.billingOperatorId}} = b.r_operator
                         AND p.{{QbPublication.exposureUnit}} IS NOT NULL
                         AND p.{{QbPublication.exposurePurchased}} IS NOT NULL
                         AND p.{{QbPublication.publisherKind}} = 'SPONSOR'
                         AND greatest(pc.{{QbPublicationCard.startAt}}, p.{{QbPublication.approvedAt}}) < b.r_to
                         AND (pc.{{QbPublicationCard.endedAt}} IS NULL OR pc.{{QbPublicationCard.endedAt}} >= b.r_from)) t)) AS r_ready
        FROM behind b
    ) w0
    -- start = a month that waits for a step: the deadline; one that waits for nothing: min(max(month end, readyAt),
    -- deadline). Reported when the run is past start + the waiting hours.
    WHERE (CASE WHEN w0.r_steps <> '' THEN w0.r_deadline
                ELSE least(greatest(w0.r_to, coalesce(w0.r_ready, w0.r_to)), w0.r_deadline) END)
          + make_interval(hours => CAST(:waitingHours AS int)) < CAST(:at AS timestamptz)
),
carried AS (
    -- The latest settlement of the operator, whatever its age (not the window's).
    SELECT o.{{QbOperator.operatorId}} AS r_operator, s.{{QbOperatorSettlement.giftCarriedOut}} AS r_carried
    FROM {{QbOperator}} o
    JOIN {{QbOperatorSettlement}} s ON s.{{QbOperatorSettlement.operatorId}} = o.{{QbOperator.operatorId}}
    WHERE o.{{QbOperator.status}} <> 'ACTIVE' AND s.{{QbOperatorSettlement.coveredFrom}} IS NOT NULL
      AND s.{{QbOperatorSettlement.giftCarriedOut}} > 0
      AND s.{{QbOperatorSettlement.month}} = (SELECT max(n.{{QbOperatorSettlement.month}}) FROM {{QbOperatorSettlement}} n
                                              WHERE n.{{QbOperatorSettlement.operatorId}} = o.{{QbOperator.operatorId}}
                                                AND n.{{QbOperatorSettlement.coveredFrom}} IS NOT NULL)
)
SELECT CAST('OPERATOR_SETTLEMENT_WAITING' AS varchar(30)) AS checkCode, CAST(w.r_operator AS varchar(128)) AS sponsorId,
       CAST(rtrim(w.r_month || ':' || w.r_steps, ':') AS varchar(128)) AS reference,
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
-- The credit carried in is what the month before carried out (when there is a settlement of the month before).
SELECT CAST('GIFT_RECOVERY' AS varchar(30)), CAST(s.r_operator AS varchar(128)), CAST(s.r_month || ':carried' AS varchar(128)),
       p.{{QbOperatorSettlement.giftCarriedOut}}, s.r_gift_carried_in
FROM new_settlements s
JOIN {{QbOperatorSettlement}} p ON p.{{QbOperatorSettlement.operatorId}} = s.r_operator
    AND p.{{QbOperatorSettlement.month}} = to_char(CAST(s.r_month || '-01' AS date) - interval '1 month', 'YYYY-MM')
WHERE p.{{QbOperatorSettlement.giftCarriedOut}} <> s.r_gift_carried_in
UNION ALL
-- Fees with no operator, charged from the earliest switch point of the operators on (the earliest real one; the epoch
-- only when there is none). By time alone, not by the operator that looks after the sponsor now (#701 review L3): that
-- changes when the sponsor moves, and a difference would go away with it, though nothing was put right; and a sponsor
-- with no operator, or one whose operator has no settlement of the new kind yet, would never be asked about. Before the
-- earliest switch point every fee was the old rule's, which needed no operator on the fee. The edges that remain
-- (#701 re-review L-5): a fee with no operator charged before that point is never asked about; and a sponsor whose
-- operator switched later than the earliest one is asked about its operator-less fees from the earliest point on,
-- though its own operator's old settlements were still the rule then (such a fee has no operator, so no settlement of
-- either rule counted it).
SELECT CAST('OPERATOR_CHARGE_NO_OPERATOR' AS varchar(30)), CAST(c.{{QbFeeCharge.sponsorId}} AS varchar(128)),
       CAST(c.{{QbFeeCharge.period}} AS varchar(128)), CAST(0 AS numeric), sum(c.{{QbFeeCharge.amount}})
FROM {{QbFeeCharge}} c
WHERE c.{{QbFeeCharge.operatorId}} IS NULL
  AND c.{{QbFeeCharge.chargedAt}} >=
      (SELECT coalesce(min(n.r_at) FILTER (WHERE n.r_at > '1970-01-02'), min(n.r_at)) FROM switch_points n)
  AND c.{{QbFeeCharge.chargedAt}} >= coalesce(CAST(:since AS timestamptz), '-infinity')
  -- A fee whose invoice is voided is turned back and nothing is owed on it: it is not asked about (how a difference is put right).
  AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} i
                  WHERE i.{{QbFeeInvoice.status}} = 'VOID'
                    AND ((i.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text))
                         OR (i.{{QbFeeInvoice.chargeId}} IS NULL
                             AND i.{{QbFeeInvoice.sponsorId}} = c.{{QbFeeCharge.sponsorId}}
                             AND i.{{QbFeeInvoice.period}} = c.{{QbFeeCharge.period}}
                             AND c.{{QbFeeCharge.billingMode}} = 'POSTPAID'
                             AND NOT EXISTS (SELECT 1 FROM {{QbFeeInvoice}} o
                                             WHERE o.{{QbFeeInvoice.chargeId}} = CAST(c.{{QbFeeCharge.chargeId}} AS text)))))
GROUP BY c.{{QbFeeCharge.sponsorId}}, c.{{QbFeeCharge.period}}
UNION ALL
SELECT CAST('OPERATOR_GIFT_CARRY_OPEN' AS varchar(30)), CAST(k.r_operator AS varchar(128)), CAST(NULL AS varchar(128)),
       CAST(0 AS numeric), k.r_carried
FROM carried k
