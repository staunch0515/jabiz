-- F-M15 a0 (docs/quizbuks/plans/F-M15.md §4.2): a manual top-up's reason is free text finance wrote, and the purge of
-- an erased Sponsor overwrites it with NULL (the column may be empty). The check of V23 required it for every manual
-- top-up, so the purge failed (and with it every other value it overwrites). The process still records a reason for each
-- manual top-up (its input requires one); only the check no longer does. The rest of the check is V23's.
ALTER TABLE qb_topup DROP CONSTRAINT qb_topup_method;
ALTER TABLE qb_topup ADD CONSTRAINT qb_topup_method CHECK (CASE method
    WHEN 'STRIPE' THEN status IN ('OPENING', 'OPEN', 'PAID', 'EXPIRED', 'FAILED') AND transfer_code IS NULL
        AND requested_amount IS NULL AND bank_reference IS NULL AND manual_kind IS NULL
    WHEN 'BANK' THEN status IN ('AWAITING', 'REPORTED', 'REVIEW', 'PAID', 'EXPIRED', 'CANCELLED')
        AND transfer_code IS NOT NULL AND requested_amount IS NOT NULL AND checkout_session_id IS NULL
        AND payment_intent_id IS NULL AND manual_kind IS NULL
    ELSE status IN ('REVIEW', 'PAID', 'CANCELLED') AND transfer_code IS NOT NULL AND requested_amount IS NOT NULL
        AND checkout_session_id IS NULL AND payment_intent_id IS NULL AND manual_kind IS NOT NULL
        AND recorded_by IS NOT NULL END);
