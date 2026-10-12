-- Payout accounts, transfers and the platform's bank payouts (docs/quizbuks/02-design.md section 3.4,
-- docs/quizbuks/plans/Q6-payments.md, Q6b). Ordinary tables written only by the processes; the constraints hold
-- whatever a process does: one payout account per user and one user per Stripe account, at most one unfinished
-- transfer per user, each transfer's postings and Stripe transfer once, amounts that add up, a payout recorded once.

CREATE TABLE qb_payout_account (
    payout_account_id   varchar(36)   PRIMARY KEY,
    user_id             varchar(128)  NOT NULL,
    provider            varchar(20)   NOT NULL CHECK (provider = 'STRIPE_CONNECT'),
    external_account_id varchar(255)  NOT NULL,
    country             varchar(2),
    status              varchar(20)   NOT NULL CHECK (status IN ('PENDING', 'ENABLED', 'RESTRICTED', 'DEAUTHORIZED')),
    payouts_enabled     boolean       NOT NULL,
    details_submitted   boolean       NOT NULL,
    requirements_due    varchar(1000),
    stripe_updated_at   timestamptz,
    livemode            boolean       NOT NULL,
    created_at          timestamptz   NOT NULL,
    version             bigint        NOT NULL,
    CONSTRAINT uk_qb_payout_account_user UNIQUE (user_id),
    CONSTRAINT uk_qb_payout_account_external UNIQUE (external_account_id),
    CONSTRAINT qb_payout_account_enabled CHECK (status <> 'ENABLED' OR payouts_enabled)
);

CREATE TABLE qb_payout_onboarding (
    onboarding_id varchar(36)   PRIMARY KEY,
    user_id       varchar(128)  NOT NULL,
    url           varchar(2048) NOT NULL,
    expires_at    timestamptz   NOT NULL,
    version       bigint        NOT NULL,
    CONSTRAINT uk_qb_payout_onboarding_user UNIQUE (user_id)
);

CREATE TABLE qb_transfer (
    transfer_id             varchar(36)    PRIMARY KEY,
    user_id                 varchar(128)   NOT NULL,
    external_account_id     varchar(255)   NOT NULL,
    amount                  numeric(19, 0) NOT NULL,
    fee                     numeric(19, 0) NOT NULL CHECK (fee >= 0),
    net                     numeric(19, 0) NOT NULL CHECK (net > 0),
    status                  varchar(20)    NOT NULL CHECK (status IN ('REVIEW', 'SENDING', 'SENT', 'FAILED', 'CHECK',
                                                                      'REJECTED', 'REVERSED')),
    approval_request_id     varchar(64),
    stripe_transfer_id      varchar(255),
    send_transaction_id     varchar(64),
    settle_transaction_id   varchar(64),
    reversal_transaction_id varchar(64),
    reversed_amount         numeric(19, 0) NOT NULL DEFAULT 0,
    failure_code            varchar(100),
    failure_message         varchar(200),
    created_at              timestamptz    NOT NULL,
    sent_at                 timestamptz,
    failed_at               timestamptz,
    check_requested_at      timestamptz,
    version                 bigint         NOT NULL,
    CONSTRAINT uk_qb_transfer_stripe UNIQUE (stripe_transfer_id),
    CONSTRAINT qb_transfer_send_posting UNIQUE (send_transaction_id),
    CONSTRAINT qb_transfer_settle_posting UNIQUE (settle_transaction_id),
    CONSTRAINT qb_transfer_adds_up CHECK (amount = fee + net),
    CONSTRAINT qb_transfer_reversed CHECK (reversed_amount >= 0 AND reversed_amount <= net),
    -- Settled transfers have their Stripe transfer and both postings; failed ones their reversal.
    CONSTRAINT qb_transfer_sent CHECK (status NOT IN ('SENT', 'REVERSED') OR (stripe_transfer_id IS NOT NULL
        AND send_transaction_id IS NOT NULL AND settle_transaction_id IS NOT NULL)),
    CONSTRAINT qb_transfer_failed CHECK (status <> 'FAILED' OR reversal_transaction_id IS NOT NULL),
    CONSTRAINT qb_transfer_unposted CHECK (status NOT IN ('REVIEW', 'REJECTED') OR send_transaction_id IS NULL)
);
-- At most one unfinished transfer per user, whatever runs at the same time.
CREATE UNIQUE INDEX qb_transfer_unfinished_idx ON qb_transfer (user_id)
    WHERE status IN ('REVIEW', 'SENDING', 'CHECK');
-- A user's own transfers, newest first (and the failure streak); the transfers in doubt (the hourly check).
CREATE INDEX qb_transfer_user_idx ON qb_transfer (user_id, created_at DESC);
CREATE INDEX qb_transfer_open_idx ON qb_transfer (status, created_at) WHERE status IN ('SENDING', 'CHECK');

CREATE TABLE qb_stripe_payout (
    payout_row_id           varchar(36)    PRIMARY KEY,
    payout_id               varchar(255)   NOT NULL,
    amount                  numeric(19, 0) NOT NULL CHECK (amount >= 0),
    status                  varchar(20)    NOT NULL CHECK (status IN ('PAID', 'FAILED')),
    paid_at                 timestamptz,
    ledger_transaction_id   varchar(64),
    reversal_transaction_id varchar(64),
    version                 bigint         NOT NULL,
    CONSTRAINT uk_qb_stripe_payout_id UNIQUE (payout_id),
    CONSTRAINT qb_stripe_payout_posting UNIQUE (ledger_transaction_id)
);

-- The daily transfer run selects the takers whose cached balance reaches the threshold.
CREATE INDEX qb_wallet_taker_balance_idx ON qb_wallet (balance) WHERE kind = 'TAKER';
