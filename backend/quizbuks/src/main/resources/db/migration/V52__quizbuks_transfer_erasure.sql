-- Q71 (docs/quizbuks/plans/Q71-taker-erasure.md; platform decision D62): a withdrawal is a record the accounts keep
-- for ten years, but who was paid goes with an erased account: the purge sets the holder's name and the account number
-- to NULL. A bank withdrawal still names its bank, branch and account type; its holder and number are written by the
-- request (QB_WITHDRAWAL_REQUEST copies the taker's account) and may only be erased afterwards.
ALTER TABLE qb_transfer DROP CONSTRAINT qb_transfer_method;
ALTER TABLE qb_transfer ADD CONSTRAINT qb_transfer_method CHECK (CASE method
    WHEN 'STRIPE' THEN bank_code IS NULL AND account_number IS NULL AND paid_reference IS NULL
    ELSE bank_code IS NOT NULL AND bank_name IS NOT NULL AND branch_code IS NOT NULL AND branch_name IS NOT NULL
        AND account_type IS NOT NULL AND stripe_transfer_id IS NULL AND status NOT IN ('CHECK', 'REVERSED') END);
