-- F-M15c (docs/quizbuks/plans/impl/F-M15.md §5.2 steps 3 and 9, §5.5): the wind-up of an erased sponsor's account
-- closes their AI credit (everything given back, the prepayment released for the erasure) and gives what their credit
-- notes have left back to their cash. Only constraints and a new table: the append-only tables gain nothing renamed
-- (decision D58; the integrity seal's digest reads the values, not the constraints).

-- The AI credit closed at the wind-up: released for the account's erasure.
ALTER TABLE qb_prepayment DROP CONSTRAINT qb_prepayment_release_reason_check;
ALTER TABLE qb_prepayment ADD CONSTRAINT qb_prepayment_release_reason_check
    CHECK (release_reason IN ('WITHDRAWN', 'REJECTED', 'REVIEW_EXPIRED', 'ENDED', 'ERASED'));

-- The AI credit's movement of that close: everything given back (negative, as a return).
ALTER TABLE qb_ai_credit_movement_version DROP CONSTRAINT qb_ai_credit_movement_version_kind_check;
ALTER TABLE qb_ai_credit_movement_version ADD CONSTRAINT qb_ai_credit_movement_version_kind_check
    CHECK (kind IN ('BUY', 'RETURN', 'CHARGE', 'EXPIRE', 'CLOSE'));

-- The credit a sponsor's credit notes have left, given back to their cash at the wind-up: a use of the credit as one
-- that paid an invoice (so that every reading of the credit left, "total - given back - used", counts it), naming the
-- cash lot it made instead of an invoice. Exactly one of the two; the rows before this all name an invoice.
ALTER TABLE qb_fee_credit_note_use_version ADD COLUMN cash_lot_id varchar(36);
ALTER TABLE qb_fee_credit_note_use_version ALTER COLUMN invoice_id DROP NOT NULL;
ALTER TABLE qb_fee_credit_note_use_version ADD CONSTRAINT qb_fee_credit_note_use_target
    CHECK ((invoice_id IS NULL) <> (cash_lot_id IS NULL));
