-- Q89d-1 (docs/quizbuks/plans/Q89-sponsored-benefits.md, D-Q89-12): a purchase can be suspended (its running
-- publications paused, its unused prizes voided on request) and resumed; who did it, when and why. Append-only: columns
-- added only.
ALTER TABLE qb_benefit_purchase_version
    ADD COLUMN suspended_at   timestamptz,
    ADD COLUMN suspended_by   varchar(128),
    ADD COLUMN suspend_reason varchar(500),
    ADD COLUMN void_unused    boolean,
    ADD COLUMN resumed_at     timestamptz;

-- What went wrong with a purchase (or one of its prizes), recorded by either side, and how it ended. An ordinary
-- table written by the processes only; the history is in the audit.
CREATE TABLE qb_benefit_incident (
    incident_id    varchar(36)  PRIMARY KEY,
    purchase_id    varchar(36)  NOT NULL,
    redemption_id  varchar(36),
    sponsor_id     varchar(128) NOT NULL,
    provider_id    varchar(128) NOT NULL,
    kind           varchar(20)  NOT NULL CHECK (kind IN ('NOT_FULFILLED', 'CLOSED_BUSINESS', 'QUANTITY_MISMATCH',
                                                         'QUALITY', 'COMPLAINT', 'OTHER')),
    note           varchar(2000) NOT NULL,
    recorded_by    varchar(128) NOT NULL,
    recorded_at    timestamptz  NOT NULL,
    resolution     varchar(2000),
    resolved_by    varchar(128),
    resolved_at    timestamptz,
    version        bigint       NOT NULL
);
CREATE INDEX qb_benefit_incident_purchase_idx ON qb_benefit_incident (purchase_id, recorded_at);
