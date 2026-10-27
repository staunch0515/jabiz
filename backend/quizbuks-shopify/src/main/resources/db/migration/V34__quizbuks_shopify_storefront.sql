-- QuizBuks for Shopify, S2a (docs/quizbuks/shopify/S2-storefront.md): a shop's quiz campaigns, the people its
-- storefront tells apart, and its pool of discount codes. Ordinary tables written by the module's processes only; a
-- shop's rows are deleted when Shopify asks to erase its data (shop/redact).

-- A campaign: a draft the staff edit, then live on the storefront as the engine's publication of the shop. At most one
-- runs (live or paused) per shop: the storefront's block needs no setting to know which.
CREATE TABLE qbs_campaign (
    campaign_id    varchar(36)   PRIMARY KEY,
    shop_id        varchar(36)   NOT NULL,
    shop_domain    varchar(255)  NOT NULL,
    status         varchar(6)    NOT NULL CHECK (status IN ('DRAFT', 'LIVE', 'PAUSED', 'ENDED')),
    name           varchar(100)  NOT NULL,
    video          varchar(2000),
    time_limit_sec numeric(5, 0) NOT NULL,
    pass_score     numeric(3, 0) NOT NULL,
    questions      text          NOT NULL,
    percent        numeric(2, 0) NOT NULL CHECK (percent BETWEEN 1 AND 90),
    player         varchar(80),
    publication_id varchar(36),
    revision       numeric(9, 0) NOT NULL,
    created_at     timestamptz   NOT NULL,
    edited_at      timestamptz   NOT NULL,
    published_at   timestamptz,
    ended_at       timestamptz,
    version        bigint        NOT NULL
);
CREATE INDEX qbs_campaign_shop_idx ON qbs_campaign (shop_domain, created_at DESC);
CREATE UNIQUE INDEX qbs_campaign_running_idx ON qbs_campaign (shop_domain) WHERE status IN ('LIVE', 'PAUSED');
CREATE UNIQUE INDEX qbs_campaign_publication_idx ON qbs_campaign (publication_id) WHERE publication_id IS NOT NULL;

-- A signed-in customer of a shop who answered its quiz. The engine knows them only as qbs:<taker_id>; erasing the
-- customer's data deletes the row, so nothing left links the engine's attempts or the codes to them. (A visitor who is
-- not signed in has no row: the engine knows them by a digest of the shop and the key their browser keeps.)
CREATE TABLE qbs_taker (
    taker_id    varchar(36)  PRIMARY KEY,
    shop_domain varchar(255) NOT NULL,
    customer_id varchar(40)  NOT NULL,
    created_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL,
    CONSTRAINT uk_qbs_taker UNIQUE (shop_domain, customer_id)
);

-- A batch of codes: one Shopify discount (a percentage off everything, until ends_at) and its codes, of one campaign.
-- CREATING while Shopify adds the codes; READY once they are added; RETIRED once seven days or fewer remain, its
-- shop uninstalled the app or Shopify never finished adding them (no more codes are handed out of it). Deleted with
-- its codes (and its discount from the shop) once its end passed: a shop holds about two weeks of batches.
CREATE TABLE qbs_discount_batch (
    batch_id         varchar(36)   PRIMARY KEY,
    shop_domain      varchar(255)  NOT NULL,
    campaign_id      varchar(36)   NOT NULL,
    percent          numeric(2, 0) NOT NULL,
    discount_id      varchar(255)  NOT NULL,
    bulk_creation_id varchar(255),
    status           varchar(8)    NOT NULL CHECK (status IN ('CREATING', 'READY', 'RETIRED')),
    code_count       numeric(4, 0) NOT NULL,
    created_at       timestamptz   NOT NULL,
    ends_at          timestamptz   NOT NULL,
    version          bigint        NOT NULL
);
CREATE INDEX qbs_discount_batch_shop_idx ON qbs_discount_batch (shop_domain, status, ends_at);

-- A code of a batch, sealed (SealedSecrets under the shop's address). AVAILABLE once added (its batch says whether it
-- can be handed out: READY and more than seven days left); ISSUED to one attempt (once: the attempt's code is the same
-- however often it is asked for); FAILED when Shopify did not add it. Deleted with its batch.
CREATE TABLE qbs_discount_code (
    code_id     varchar(36)   PRIMARY KEY,
    batch_id    varchar(36)   NOT NULL,
    shop_domain varchar(255)  NOT NULL,
    seq         numeric(4, 0) NOT NULL,
    sealed_code text          NOT NULL,
    hint        varchar(8)    NOT NULL,
    status      varchar(9)    NOT NULL CHECK (status IN ('AVAILABLE', 'ISSUED', 'FAILED')),
    campaign_id varchar(36),
    taker       varchar(128),
    attempt_id  varchar(36),
    issued_at   timestamptz,
    version     bigint        NOT NULL,
    CONSTRAINT uk_qbs_discount_code_seq UNIQUE (batch_id, seq)
);
CREATE INDEX qbs_discount_code_pool_idx ON qbs_discount_code (shop_domain, status, batch_id, seq);
CREATE INDEX qbs_discount_code_issued_idx ON qbs_discount_code (shop_domain, issued_at) WHERE issued_at IS NOT NULL;
CREATE INDEX qbs_discount_code_taker_idx ON qbs_discount_code (taker) WHERE taker IS NOT NULL;
CREATE UNIQUE INDEX qbs_discount_code_attempt_idx ON qbs_discount_code (attempt_id) WHERE attempt_id IS NOT NULL;

-- S2 holds customer data: a data request is reported, an erasure done.
ALTER TABLE qbs_compliance_request DROP CONSTRAINT qbs_compliance_request_outcome_check;
ALTER TABLE qbs_compliance_request ADD CONSTRAINT qbs_compliance_request_outcome_check
    CHECK (outcome IN ('NO_DATA', 'DELETED', 'KEPT_REINSTALLED', 'NOT_FOUND', 'MISMATCH', 'REPORTED', 'REDACTED'));
-- What a data request found, for the shop's staff to pass on: the customer's attempts and codes, as JSON.
ALTER TABLE qbs_compliance_request ADD COLUMN customer_id varchar(64), ADD COLUMN report text;
