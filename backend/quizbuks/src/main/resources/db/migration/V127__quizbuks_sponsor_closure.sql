-- F-M15 b (docs/quizbuks/plans/impl/F-M15.md §4.1): the wind-up of an erased sponsor's platform fee account, one row a
-- sponsor who had money in it. Ids, states and reason codes only: the amounts are summed from the ledger and the
-- payment parts, and no personal text is kept. An ordinary table (its state changes).
CREATE TABLE qb_sponsor_closure (
    closure_id  varchar(36)  PRIMARY KEY,
    sponsor_id  varchar(128) NOT NULL,
    status      varchar(10)  NOT NULL CHECK (status IN ('WAITING', 'CLOSED')),
    reason_code varchar(20)  CHECK (reason_code IN ('SWITCH_OFF', 'DEPOSIT_OPEN', 'FEE_OWED')),
    erased_at   timestamptz  NOT NULL,
    last_run_at timestamptz,
    closed_at   timestamptz,
    version     bigint       NOT NULL DEFAULT 1,
    CONSTRAINT qb_sponsor_closure_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);
-- One a sponsor: a replay of the erasure's event registers nothing more.
CREATE UNIQUE INDEX qb_sponsor_closure_sponsor_idx ON qb_sponsor_closure (sponsor_id);
-- The daily job's items (what is waiting, what closed with something owed).
CREATE INDEX qb_sponsor_closure_status_idx ON qb_sponsor_closure (status, reason_code);
