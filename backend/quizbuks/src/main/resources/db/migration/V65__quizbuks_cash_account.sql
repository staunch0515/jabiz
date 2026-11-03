-- Q87a (docs/quizbuks/plans/Q87-fee-account.md): the sponsors' cash accounts beside their Kudos wallets.

-- A sponsor's cash account is a second wallet row of the same owner (kind CASH), the cache of account 2160 for them.
ALTER TABLE qb_wallet DROP CONSTRAINT qb_wallet_kind_check;
ALTER TABLE qb_wallet ADD CONSTRAINT qb_wallet_kind_check CHECK (kind IN ('SPONSOR', 'TAKER', 'CASH'));

-- Where a top-up is credited: the Kudos wallet (every top-up before Q87a) or the cash account. Never changed.
ALTER TABLE qb_topup ADD COLUMN target varchar(10) NOT NULL DEFAULT 'KUDOS' CHECK (target IN ('KUDOS', 'CASH'));
-- A manual credit (promotion, money received by hand) stays a credit of Kudos.
ALTER TABLE qb_topup ADD CONSTRAINT qb_topup_manual_kudos CHECK (method <> 'MANUAL' OR target = 'KUDOS');

-- Each exchange between a sponsor's cash and Kudos: the source of its posting, written once.
CREATE TABLE qb_kudos_exchange (
    exchange_id varchar(36)    PRIMARY KEY,
    sponsor_id  varchar(128)   NOT NULL,
    direction   varchar(10)    NOT NULL CHECK (direction IN ('TO_KUDOS', 'TO_CASH')),
    amount      numeric(19, 0) NOT NULL CHECK (amount > 0),
    created_at  timestamptz    NOT NULL,
    version     bigint         NOT NULL
);
CREATE INDEX qb_kudos_exchange_sponsor_idx ON qb_kudos_exchange (sponsor_id, created_at DESC);
