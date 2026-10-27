-- QuizBuks for Shopify, S3a (docs/quizbuks/shopify/S3-billing.md): a shop's plan as Shopify last said it, and the
-- attempts its storefront completed in each billing period. Ordinary tables written by the module's processes only; a
-- shop's rows are deleted when Shopify asks to erase its data (shop/redact).

-- One row per shop, written when Shopify's subscriptions are read; deleted at an uninstall (Shopify cancels the
-- subscription: the shop is on the free plan until its plan is read again). FREE without an active subscription; after
-- a move to a lower plan, the higher one is kept for the period it was paid for (kept_from to kept_until).
CREATE TABLE qbs_shop_plan (
    shop_id           varchar(36)   PRIMARY KEY,
    shop_domain       varchar(255)  NOT NULL,
    plan              varchar(7)    NOT NULL CHECK (plan IN ('FREE', 'STARTER', 'GROWTH', 'PRO')),
    subscription_id   varchar(255),
    subscription_name varchar(255),
    test              boolean       NOT NULL,
    trial_ends_at     timestamptz,
    period_end        timestamptz,
    kept_plan         varchar(7)    CHECK (kept_plan IN ('FREE', 'STARTER', 'GROWTH', 'PRO')),
    kept_from         timestamptz,
    kept_until        timestamptz,
    checked_at        timestamptz   NOT NULL,
    version           bigint        NOT NULL,
    CONSTRAINT uk_qbs_shop_plan_domain UNIQUE (shop_domain)
);

-- The attempts a shop's storefront completed in one period (30 days of a paid plan, or a calendar month).
CREATE TABLE qbs_usage (
    usage_id     varchar(36)   PRIMARY KEY,
    shop_domain  varchar(255)  NOT NULL,
    period_start timestamptz   NOT NULL,
    period_end   timestamptz   NOT NULL,
    completed    numeric(9, 0) NOT NULL,
    version      bigint        NOT NULL,
    CONSTRAINT uk_qbs_usage_period UNIQUE (shop_domain, period_start)
);
