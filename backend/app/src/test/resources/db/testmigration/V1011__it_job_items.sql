-- Items of the per-item job tests (com.jabiz.app.it.fixture.ItJobItemFixtures, ROADMAP phase 16k): each row says how
-- its item process behaves; f_runs counts the item's committed runs.
CREATE TABLE it_job_item (
    f_id        varchar(36)   PRIMARY KEY,
    f_batch     varchar(36)   NOT NULL,
    f_seq       numeric(9, 0) NOT NULL,
    f_due       boolean       NOT NULL DEFAULT true,
    f_stay_due  boolean       NOT NULL DEFAULT false,
    f_fail      boolean       NOT NULL DEFAULT false,
    f_sleep_ms  numeric(9, 0) NOT NULL DEFAULT 0,
    f_gate      varchar(100),
    f_lock1     varchar(100),
    f_pause     varchar(100),
    f_lock2     varchar(100),
    f_insert    varchar(200),
    f_runs      numeric(9, 0) NOT NULL DEFAULT 0,
    f_version   bigint        NOT NULL DEFAULT 1
);
CREATE INDEX it_job_item_batch_idx ON it_job_item (f_batch, f_seq);
