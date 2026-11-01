-- Q70 (docs/quizbuks/plans/Q70-referrals-and-rounds.md): referral advertising. A publication may welcome referrals
-- (its policy as one JSON column, frozen at submission and on the card); a referrer's link per publication; a referral
-- per referee and publication; the three reward records learn their source (an attempt, or a referral whose id then
-- stands in attempt_id). The sealed tables only gain columns (platform decision D58).

ALTER TABLE qb_publication_version ADD COLUMN referral varchar(2000);
ALTER TABLE qb_publication_card ADD COLUMN referral varchar(2000);

ALTER TABLE qb_cash_reward_version ADD COLUMN source_kind varchar(10)
    CHECK (source_kind IS NULL OR source_kind IN ('ATTEMPT', 'REFERRAL'));
ALTER TABLE qb_redemption_version ADD COLUMN source_kind varchar(10)
    CHECK (source_kind IS NULL OR source_kind IN ('ATTEMPT', 'REFERRAL'));
ALTER TABLE qb_digital_prize_version ADD COLUMN source_kind varchar(10)
    CHECK (source_kind IS NULL OR source_kind IN ('ATTEMPT', 'REFERRAL'));

CREATE TABLE qb_referral_link (
    link_id        varchar(36)  PRIMARY KEY,
    publication_id varchar(36)  NOT NULL,
    referrer_id    varchar(128),
    link_key       varchar(64)  NOT NULL,
    created_at     timestamptz  NOT NULL,
    version        bigint       NOT NULL,
    CONSTRAINT uk_qb_referral_link_key UNIQUE (link_key),
    CONSTRAINT uk_qb_referral_link_referrer UNIQUE (publication_id, referrer_id)
);

CREATE TABLE qb_referral (
    referral_id      varchar(36)   PRIMARY KEY,
    publication_id   varchar(36)   NOT NULL,
    publisher_kind   varchar(20)   NOT NULL CHECK (publisher_kind IN ('SPONSOR', 'SHOPIFY_SHOP')),
    publisher_id     varchar(128)  NOT NULL,
    referrer_id      varchar(128),
    referee_id       varchar(128),
    link_id          varchar(36)   NOT NULL,
    status           varchar(10)   NOT NULL CHECK (status IN ('REFERRED', 'REWARDED', 'EXPIRED', 'VOID')),
    referred_at      timestamptz   NOT NULL,
    expires_at       timestamptz   NOT NULL,
    reward_method    varchar(20)   NOT NULL,
    amount           numeric(19, 0) NOT NULL,
    attempt_id       varchar(36),
    rewarded_at      timestamptz,
    reward_record_id varchar(36),
    ledger_transaction_id varchar(36),
    funds_short      boolean       NOT NULL DEFAULT false,
    ended_at         timestamptz,
    void_reason      varchar(40),
    version          bigint        NOT NULL,
    CONSTRAINT uk_qb_referral_referee UNIQUE (publication_id, referee_id)
);
CREATE INDEX qb_referral_due_idx ON qb_referral (status, expires_at);
CREATE INDEX qb_referral_publication_idx ON qb_referral (publication_id, status);
CREATE INDEX qb_referral_referrer_idx ON qb_referral (referrer_id, publication_id, referred_at);
CREATE INDEX qb_referral_referee_idx ON qb_referral (referee_id);
CREATE INDEX qb_referral_publisher_idx ON qb_referral (publisher_id, referred_at);
CREATE INDEX qb_referral_link_idx ON qb_referral (link_id);
