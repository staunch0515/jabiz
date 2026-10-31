-- Public counters (decision D65; docs/design/15-public-access.md section 10): what anonymous visitors did on public
-- pages, by counter, key and day. Written only by PublicCounters, which adds the counts it held in memory; the platform
-- entity PublicCounterDay reads it. Nothing personal: no address, browser or time of day.
CREATE TABLE sys_public_counter_day (
    row_id       uuid         PRIMARY KEY,
    counter_name varchar(60)  NOT NULL,
    counter_key  varchar(100) NOT NULL,
    day          date         NOT NULL,
    hits         bigint       NOT NULL CHECK (hits >= 0),
    UNIQUE (counter_name, counter_key, day)
);
-- Reading a counter's days across keys (a key's days are read through the unique index).
CREATE INDEX sys_public_counter_day_day_idx ON sys_public_counter_day (counter_name, day);
