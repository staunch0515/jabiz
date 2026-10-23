-- Top-ups by bank transfer (docs/quizbuks/plans/Q13-bank-transfer.md, Q13a): a top-up is paid by card through Stripe
-- (every existing row) or by a transfer to the platform's bank account. The sponsor asks for one (AWAITING, with a
-- code to put before the payer's name), may say it is sent (REPORTED), finance records the money found on the
-- statement (REVIEW) and another administrator approves it: only then is it posted (debit 1120 / credit 2110) and PAID.
-- A rejection sends it back to where it was (review_from). The amount becomes what was received; the sponsor's own
-- figure stays as requested_amount.
ALTER TABLE qb_topup
    ADD COLUMN method              varchar(10)    NOT NULL DEFAULT 'STRIPE' CHECK (method IN ('STRIPE', 'BANK')),
    ADD COLUMN transfer_code       varchar(10),
    ADD COLUMN requested_amount    numeric(19, 0) CHECK (requested_amount > 0),
    ADD COLUMN payer_name          varchar(48),
    ADD COLUMN transfer_date       date,
    ADD COLUMN reported_at         timestamptz,
    ADD COLUMN received_on         date,
    ADD COLUMN bank_reference      varchar(64),
    ADD COLUMN recorded_by         varchar(128),
    ADD COLUMN recorded_at         timestamptz,
    ADD COLUMN review_from         varchar(20),
    ADD COLUMN approval_request_id varchar(64);

ALTER TABLE qb_topup DROP CONSTRAINT qb_topup_status_check;
ALTER TABLE qb_topup ADD CONSTRAINT qb_topup_status_check CHECK (status IN ('OPENING', 'OPEN', 'PAID', 'EXPIRED',
    'FAILED', 'AWAITING', 'REPORTED', 'REVIEW', 'CANCELLED'));

-- A paid card top-up has its payment; a paid bank one what finance recorded. Each method keeps to its own states.
ALTER TABLE qb_topup DROP CONSTRAINT qb_topup_paid_posted;
ALTER TABLE qb_topup ADD CONSTRAINT qb_topup_paid_posted CHECK (status <> 'PAID' OR (ledger_transaction_id IS NOT NULL
    AND (payment_intent_id IS NOT NULL OR (method = 'BANK' AND received_on IS NOT NULL
        AND bank_reference IS NOT NULL AND recorded_by IS NOT NULL AND approval_request_id IS NOT NULL))));
ALTER TABLE qb_topup ADD CONSTRAINT qb_topup_method CHECK (CASE method
    WHEN 'STRIPE' THEN status IN ('OPENING', 'OPEN', 'PAID', 'EXPIRED', 'FAILED') AND transfer_code IS NULL
        AND requested_amount IS NULL AND bank_reference IS NULL
    ELSE status IN ('AWAITING', 'REPORTED', 'REVIEW', 'PAID', 'EXPIRED', 'CANCELLED') AND transfer_code IS NOT NULL
        AND requested_amount IS NOT NULL AND checkout_session_id IS NULL AND payment_intent_id IS NULL END);
-- Under review: what was received is recorded, and where a rejection sends it back to.
ALTER TABLE qb_topup ADD CONSTRAINT qb_topup_review CHECK (status <> 'REVIEW' OR (received_on IS NOT NULL
    AND bank_reference IS NOT NULL AND recorded_by IS NOT NULL AND review_from IS NOT NULL
    AND approval_request_id IS NOT NULL));
ALTER TABLE qb_topup ADD CONSTRAINT qb_topup_review_from CHECK (review_from IN ('AWAITING', 'REPORTED', 'EXPIRED',
    'CANCELLED'));

ALTER TABLE qb_topup ADD CONSTRAINT uk_qb_topup_transfer_code UNIQUE (transfer_code);
-- One entry of the bank statement is recorded against one top-up at most, whoever records it: the database refuses
-- a second (a rejection clears it).
CREATE UNIQUE INDEX uk_qb_topup_bank_reference ON qb_topup (received_on, bank_reference)
    WHERE bank_reference IS NOT NULL;
-- The bank top-ups waiting for money (the daily expiry, a sponsor's open ones) and finance's list.
CREATE INDEX qb_topup_bank_open_idx ON qb_topup (created_at) WHERE status IN ('AWAITING', 'REPORTED');
CREATE INDEX qb_topup_method_idx ON qb_topup (method, status, created_at);
