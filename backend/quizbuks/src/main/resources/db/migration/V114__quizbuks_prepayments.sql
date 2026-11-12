-- F-M5 (docs/quizbuks/first-launch-spec.md F-M5, docs/quizbuks/plans/impl/F-M.md §8): what a publication's
-- submission sets aside of the sponsor's account for its fees (account 2180), charged from when it goes on, given back
-- when it goes back to its draft or a day after it ends; F-M11: the operator its fees belong to, frozen at the
-- submission. The audience rules' fee (F-M7) is the first fee set aside.

-- One prepayment a publication and purpose (an ordinary table: its remaining amount and status change).
CREATE TABLE qb_prepayment (
    prepayment_id  varchar(36)    PRIMARY KEY,
    sponsor_id     varchar(128)   NOT NULL,
    publication_id varchar(36),
    purpose        varchar(20)    NOT NULL CHECK (purpose IN ('DISTRIBUTION', 'EXPOSURE', 'AUDIENCE', 'AI')),
    mode           varchar(10)    NOT NULL CHECK (mode IN ('PREPAID', 'POSTPAID')),
    amount         numeric(19, 0) NOT NULL CHECK (amount > 0),
    remaining      numeric(19, 0) NOT NULL,
    charge_amount  numeric(19, 0) NOT NULL CHECK (charge_amount >= 0),
    operator_id    varchar(36),
    detail         varchar(4000),
    status         varchar(10)    NOT NULL CHECK (status IN ('OPEN', 'RELEASED')),
    frozen_at      timestamptz    NOT NULL,
    charged_at     timestamptz,
    released_at    timestamptz,
    release_reason varchar(20)    CHECK (release_reason IN ('WITHDRAWN', 'REJECTED', 'REVIEW_EXPIRED', 'ENDED')),
    version        bigint         NOT NULL DEFAULT 1,
    CONSTRAINT qb_prepayment_remaining CHECK (remaining >= 0 AND remaining <= amount),
    CONSTRAINT qb_prepayment_released CHECK ((status = 'RELEASED') = (released_at IS NOT NULL))
);
-- At most one open prepayment a publication and purpose (a resubmission sets aside again only after the release).
CREATE UNIQUE INDEX qb_prepayment_open_purpose_idx ON qb_prepayment (publication_id, purpose) WHERE status = 'OPEN';
CREATE INDEX qb_prepayment_publication_idx ON qb_prepayment (publication_id);
CREATE INDEX qb_prepayment_sponsor_status_idx ON qb_prepayment (sponsor_id, status);

-- A payment part may be aimed at a prepayment (set aside, given back), and a charge's part may be paid from one.
ALTER TABLE qb_fee_payment_version DROP CONSTRAINT qb_fee_payment_version_target_entity_check;
ALTER TABLE qb_fee_payment_version ADD CONSTRAINT qb_fee_payment_version_target_entity_check
    CHECK (target_entity IN ('QbFeeCharge', 'QbFeeInvoice', 'QbPrepayment'));
ALTER TABLE qb_fee_payment_version ADD COLUMN prepayment_id varchar(36);
CREATE INDEX qb_fee_payment_version_prepayment_idx ON qb_fee_payment_version (prepayment_id)
    WHERE prepayment_id IS NOT NULL;

-- The audience rules' fee, charged from its prepayment when the publication goes on.
ALTER TABLE qb_fee_charge_version DROP CONSTRAINT qb_fee_charge_version_kind_check;
ALTER TABLE qb_fee_charge_version ADD CONSTRAINT qb_fee_charge_version_kind_check
    CHECK (kind IN ('MONTHLY', 'MANUAL', 'AUDIENCE'));

-- F-M11, F-M5: on the publication, what its submission froze (cleared when it goes back to its draft).
ALTER TABLE qb_publication_version ADD COLUMN billing_operator_id varchar(36);
ALTER TABLE qb_publication_version ADD COLUMN prepayment_total numeric(19, 0);

-- Finding the ended publications whose prepayments the daily job gives back.
CREATE INDEX qb_prepayment_open_frozen_idx ON qb_prepayment (frozen_at) WHERE status = 'OPEN';
