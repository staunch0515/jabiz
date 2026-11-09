-- F-C4 (docs/quizbuks/plans/impl/F-C4.md): the records of the sponsor packages' imports. The number is temporary
-- (the base's last is V99): renumbered to the highest on 1.3/quizbuks + 1
-- before merging.

-- One row per import a Sponsor starts (QB_PACKAGE_IMPORT_START / _STEP / _ABANDON only, every change audited by the
-- platform): the package's hash, how it imports, its state, and the steps done with the ids they made (JSON, no content
-- and no secret). An ordinary table: deleted after qb.package.import-retention-days and with the Sponsor's account.
CREATE TABLE qb_package_import (
    import_id      varchar(36)   PRIMARY KEY,
    sponsor_id     varchar(128)  NOT NULL,
    package_hash   varchar(64)   NOT NULL,
    format_version numeric(3, 0) NOT NULL,
    mode           varchar(10)   NOT NULL CHECK (mode IN ('FULL', 'PROFILE')),
    status         varchar(10)   NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'ABANDONED')),
    batch_size     numeric(6, 0) NOT NULL,
    steps_total    numeric(6, 0) NOT NULL,
    steps_done     numeric(6, 0) NOT NULL,
    last_step      varchar(200),
    done           text          NOT NULL,
    started_at     timestamptz   NOT NULL,
    updated_at     timestamptz   NOT NULL,
    finished_at    timestamptz,
    version        bigint        NOT NULL
);
-- At most one import not finished per Sponsor (the process checks it under the Sponsor's lock; this is the backstop).
CREATE UNIQUE INDEX qb_package_import_running_idx ON qb_package_import (sponsor_id) WHERE status = 'RUNNING';
-- A Sponsor's records: the console, the operators' scope and the Sponsor's details in the management system.
CREATE INDEX qb_package_import_sponsor_idx ON qb_package_import (sponsor_id, started_at);
-- The daily job's records past their retention.
CREATE INDEX qb_package_import_updated_idx ON qb_package_import (updated_at);
