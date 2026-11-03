-- Q89b-1 (docs/quizbuks/plans/Q89-sponsored-benefits.md, D-Q89-6 to D-Q89-9): a publication rewards its winners
-- with goods or services another sponsor provides, bought through a confirmed purchase. Columns are only added to
-- the append-only tables (sealed rows digest the columns they recorded; platform decision D58).

-- The purchase a draft draws on, the benefit and purchase as the draft saw them (what the review approves; a changed
-- purchase is saved again), and who hands the prize over (none: the publisher, as before).
ALTER TABLE qb_publication_version ADD COLUMN benefit_purchase_id varchar(36);
ALTER TABLE qb_publication_version ADD COLUMN benefit_snapshot varchar(4000);
ALTER TABLE qb_publication_version ADD COLUMN fulfiller_id varchar(128);

-- Frozen on the card when it goes on: the settlement writes the fulfiller into each prize without reading more.
ALTER TABLE qb_publication_card ADD COLUMN benefit_purchase_id varchar(36);
ALTER TABLE qb_publication_card ADD COLUMN fulfiller_id varchar(128);

-- A prize handed over by a sponsor other than its publisher (none: the publisher); the provider's lists read by it.
ALTER TABLE qb_redemption_version ADD COLUMN fulfiller_id varchar(128);
CREATE INDEX qb_redemption_version_fulfiller_idx ON qb_redemption_version (fulfiller_id);

-- How many of a purchase each publication holds (D-Q89-7): taken when it is submitted, released when it is back to
-- its draft or ended (then `used` keeps how many it gave). An ordinary table written under the purchase's lock.
CREATE TABLE qb_benefit_allocation (
    allocation_id   varchar(36)    PRIMARY KEY,
    purchase_id     varchar(36)    NOT NULL,
    publication_id  uuid           NOT NULL REFERENCES entity_registry (entity_id),
    sponsor_id      varchar(128)   NOT NULL,
    provider_id     varchar(128)   NOT NULL,
    quantity        numeric(9, 0)  NOT NULL CHECK (quantity >= 1),
    used            numeric(9, 0)  CHECK (used >= 0),
    status          varchar(10)    NOT NULL CHECK (status IN ('OPEN', 'RELEASED')),
    allocated_at    timestamptz    NOT NULL,
    released_at     timestamptz,
    version         bigint         NOT NULL,
    CONSTRAINT qb_benefit_allocation_one UNIQUE (publication_id),
    CONSTRAINT qb_benefit_allocation_released CHECK ((status = 'RELEASED') = (released_at IS NOT NULL)
        AND (status = 'RELEASED') = (used IS NOT NULL))
);

CREATE INDEX qb_benefit_allocation_purchase_idx ON qb_benefit_allocation (purchase_id);
CREATE INDEX qb_benefit_allocation_open_idx ON qb_benefit_allocation (status) WHERE status = 'OPEN';
