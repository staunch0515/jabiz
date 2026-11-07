-- F-C2 (docs/quizbuks/plans/impl/F-C2.md): the daily snapshot of what each sponsor occupies, one row per sponsor and
-- day (of jabiz.fiscal-year-zone) holding every meter: meters is a JSON object {meter: value} with exactly the keys of
-- usage.UsageMeters (the one list of the meters; no column per meter, so a new meter needs no migration). One row a day
-- keeps the audit at one record per sponsor and day (platform decision D27: every write is audited). An ordinary table
-- written by QB_USAGE_SNAPSHOT_ITEM only: a run of the same day overwrites its row, rows past qb.usage.retention-days
-- and those of an erased account are deleted. Renumbered to the next free version of 1.3/quizbuks before merging.
CREATE TABLE qb_usage_day (
    usage_day_id varchar(36)  PRIMARY KEY,
    sponsor_id   varchar(128) NOT NULL,
    snapshot_day date         NOT NULL,
    meters       text         NOT NULL,
    recorded_at  timestamptz  NOT NULL,
    version      bigint       NOT NULL,
    CONSTRAINT qb_usage_day_meters_check CHECK (meters IS JSON OBJECT),
    -- One row per sponsor and day; also a sponsor's days in order (their card, the retention, the months).
    CONSTRAINT uk_qb_usage_day UNIQUE (sponsor_id, snapshot_day)
);
-- Every sponsor's row of one day: the administrators' months and the check that a day has its snapshot.
CREATE INDEX qb_usage_day_day_idx ON qb_usage_day (snapshot_day);
