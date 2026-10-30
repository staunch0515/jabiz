-- Who works in which warehouse (ROADMAP phase 17y, decision D64): the warehouse entry's sessions are scoped to their
-- user's warehouse, and see and receive its stock only. An ordinary table: a person moves by changing the row, which
-- takes effect at their next refresh.
CREATE TABLE warehouse_staff (
    user_id       varchar(36)   PRIMARY KEY,
    warehouse_id  uuid          NOT NULL,
    version       bigint        NOT NULL
);
CREATE INDEX ix_warehouse_staff_warehouse ON warehouse_staff (warehouse_id);
