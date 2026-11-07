-- F-C2 (docs/quizbuks/plans/impl/F-C2.md): the daily snapshot of what each sponsor occupies, one row per sponsor, day
-- (of jabiz.fiscal-year-zone) and meter (usage.UsageMeters). An ordinary table written by QB_USAGE_SNAPSHOT_ITEM only:
-- a run of the same day overwrites its rows, rows past qb.usage.retention-days and those of an erased account are
-- deleted. The meters are listed in UsageMeters only (the field's code), not in a CHECK here, so that a new meter needs
-- no migration. Renumbered to the next free version of 1.3/quizbuks before merging.
CREATE TABLE qb_usage_day (
    usage_day_id varchar(36)    PRIMARY KEY,
    sponsor_id   varchar(128)   NOT NULL,
    snapshot_day date           NOT NULL,
    meter        varchar(32)    NOT NULL,
    value        numeric(19, 0) NOT NULL,
    recorded_at  timestamptz    NOT NULL,
    version      bigint         NOT NULL,
    CONSTRAINT qb_usage_day_value_check CHECK (value >= 0),
    -- One value per sponsor, day and meter; also a sponsor's days in order (their card, the retention, the months).
    CONSTRAINT uk_qb_usage_day UNIQUE (sponsor_id, snapshot_day, meter)
);
-- Every sponsor's rows of one day: the administrators' months and the check that a day has its snapshot.
CREATE INDEX qb_usage_day_day_idx ON qb_usage_day (snapshot_day);
