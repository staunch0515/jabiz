-- Manual credits (docs/quizbuks/plans/Q18-bank-settings-credit.md, Q18b): a finance administrator credits a sponsor's
-- wallet by hand, for money received outside the system (PAID_OUTSIDE: debit 1120, with the day and the statement's
-- reference) or as a grant (GRANT: debit 5120, no money), and another administrator approves it. A manual credit is a
-- top-up of method MANUAL: recorded under review (REVIEW), then PAID once approved or CANCELLED when rejected. It has a
-- code from the transfer codes' sequence, as its reference. The sponsor sees it only once paid: sponsor_visible, derived
-- by the database, is what the sponsor's dataset is scoped to (a process never writes it). Every existing row is a
-- card or bank top-up the sponsor sees.
ALTER TABLE qb_topup
    ADD COLUMN manual_kind     varchar(20) CHECK (manual_kind IN ('PAID_OUTSIDE', 'GRANT')),
    ADD COLUMN manual_reason   varchar(500),
    ADD COLUMN sponsor_visible boolean GENERATED ALWAYS AS (method <> 'MANUAL' OR status = 'PAID') STORED;

ALTER TABLE qb_topup DROP CONSTRAINT qb_topup_method_check;
ALTER TABLE qb_topup ADD CONSTRAINT qb_topup_method_check CHECK (method IN ('STRIPE', 'BANK', 'MANUAL'));

-- Each method keeps to its own states; only a manual credit has a kind and a reason.
ALTER TABLE qb_topup DROP CONSTRAINT qb_topup_method;
ALTER TABLE qb_topup ADD CONSTRAINT qb_topup_method CHECK (CASE method
    WHEN 'STRIPE' THEN status IN ('OPENING', 'OPEN', 'PAID', 'EXPIRED', 'FAILED') AND transfer_code IS NULL
        AND requested_amount IS NULL AND bank_reference IS NULL AND manual_kind IS NULL
    WHEN 'BANK' THEN status IN ('AWAITING', 'REPORTED', 'REVIEW', 'PAID', 'EXPIRED', 'CANCELLED')
        AND transfer_code IS NOT NULL AND requested_amount IS NOT NULL AND checkout_session_id IS NULL
        AND payment_intent_id IS NULL AND manual_kind IS NULL
    ELSE status IN ('REVIEW', 'PAID', 'CANCELLED') AND transfer_code IS NOT NULL AND requested_amount IS NOT NULL
        AND checkout_session_id IS NULL AND payment_intent_id IS NULL AND manual_kind IS NOT NULL
        AND manual_reason IS NOT NULL AND recorded_by IS NOT NULL END);
-- Money received outside has its day and reference while under review or paid (a rejection clears them, so that the
-- entry may be recorded again); a grant never has them.
ALTER TABLE qb_topup ADD CONSTRAINT qb_topup_manual_kind CHECK (CASE manual_kind
    WHEN 'PAID_OUTSIDE' THEN status = 'CANCELLED' OR (received_on IS NOT NULL AND bank_reference IS NOT NULL)
    WHEN 'GRANT' THEN received_on IS NULL AND bank_reference IS NULL
    ELSE true END);

-- A paid card top-up has its payment; a paid bank one, or a manual one, what finance recorded and its approval.
ALTER TABLE qb_topup DROP CONSTRAINT qb_topup_paid_posted;
ALTER TABLE qb_topup ADD CONSTRAINT qb_topup_paid_posted CHECK (status <> 'PAID' OR (ledger_transaction_id IS NOT NULL
    AND (payment_intent_id IS NOT NULL
        OR (method = 'BANK' AND received_on IS NOT NULL AND bank_reference IS NOT NULL AND recorded_by IS NOT NULL
            AND approval_request_id IS NOT NULL)
        OR (method = 'MANUAL' AND recorded_by IS NOT NULL AND approval_request_id IS NOT NULL))));
-- Under review: who recorded it and where a rejection sends it; a bank one also what was received and its approval.
-- A manual credit is written before its approval is asked for (the approval names it) and given the approval's
-- request in the same transaction, which is refused as a whole when no approval is asked for.
ALTER TABLE qb_topup DROP CONSTRAINT qb_topup_review;
ALTER TABLE qb_topup ADD CONSTRAINT qb_topup_review CHECK (status <> 'REVIEW' OR (recorded_by IS NOT NULL
    AND review_from IS NOT NULL AND (method = 'MANUAL' OR (received_on IS NOT NULL AND bank_reference IS NOT NULL
        AND approval_request_id IS NOT NULL))));
