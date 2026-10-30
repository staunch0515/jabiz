-- Entry scopes through a mapping on a process-only dataset (ROADMAP phase 17y-7, decision D64): notes of users,
-- limited to a warehouse session's staff through WarehouseStaff, written by a process.
CREATE TABLE it_scoped_note (
    f_id      varchar(64) PRIMARY KEY,
    f_owner   varchar(36) NOT NULL,
    f_text    text,
    f_version bigint      NOT NULL DEFAULT 1
);
