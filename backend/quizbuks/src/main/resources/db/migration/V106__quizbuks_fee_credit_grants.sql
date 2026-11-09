-- F-M2 (docs/quizbuks/plans/impl/F-M.md §M2): the platform fee Kudos granted to sponsors - by onboarding (an
-- operator's lead confirms), by hand (headquarters, four eyes) - voided when they expire, their sponsor reminded first.
-- The grants were written by F-M1's table; this adds what voiding and reminding them leave behind, and the requests by
-- hand. The promotion expenses account 5140 and its journal mapping are QB_SETUP's (no rows here).

-- What the daily job voided of a lot (its remaining amount when it expired) and when; and when its sponsor was told it
-- would expire (once a lot). "Promotion expenses = granted - voided" is checked daily by operator from these.
ALTER TABLE qb_fee_credit_grant
    ADD COLUMN voided_amount numeric(19, 0) NOT NULL DEFAULT 0,
    ADD COLUMN voided_at     timestamptz,
    ADD COLUMN reminded_at   timestamptz;
ALTER TABLE qb_fee_credit_grant ADD CONSTRAINT qb_fee_credit_grant_voided
    CHECK (voided_amount >= 0 AND remaining + voided_amount <= amount
        AND ((voided_at IS NULL AND voided_amount = 0) OR (voided_at IS NOT NULL AND remaining = 0)));
-- The daily jobs read the lots with something left by when they expire, of every sponsor.
CREATE INDEX qb_fee_credit_grant_expiry_idx ON qb_fee_credit_grant (expires_at) WHERE remaining > 0;
CREATE INDEX qb_fee_credit_grant_operator_idx ON qb_fee_credit_grant (operator_id, granted_at DESC);

-- A grant by hand is a fee request of its own kind (the same four eyes): the sponsor, the amount, why, for how many days
-- (none: the parameter's), and the grant the approval made.
ALTER TABLE qb_fee_request DROP CONSTRAINT qb_fee_request_kind_check;
ALTER TABLE qb_fee_request ADD CONSTRAINT qb_fee_request_kind_check CHECK (kind IN ('MANUAL', 'VOID', 'CREDIT'));
ALTER TABLE qb_fee_request DROP CONSTRAINT qb_fee_request_kind;
ALTER TABLE qb_fee_request
    ADD COLUMN valid_days numeric(3, 0) CHECK (valid_days BETWEEN 1 AND 999),
    ADD COLUMN grant_id   varchar(36);
ALTER TABLE qb_fee_request ADD CONSTRAINT qb_fee_request_kind
    CHECK ((kind = 'MANUAL' AND amount IS NOT NULL AND invoice_id IS NULL AND valid_days IS NULL)
        OR (kind = 'VOID' AND invoice_id IS NOT NULL AND valid_days IS NULL)
        OR (kind = 'CREDIT' AND amount IS NOT NULL AND invoice_id IS NULL));
