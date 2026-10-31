-- Public counters' owners (decision D65 item 7; docs/design/15-public-access.md section 10): whom a key belongs to,
-- taken from its row in the public dataset when counted, so that an application's dataset scoped to the reader gives
-- each owner (a sponsor) their keys' counts only. Empty for counters without an owner.
ALTER TABLE sys_public_counter_day ADD COLUMN owner_key varchar(128);
CREATE INDEX sys_public_counter_day_owner_idx ON sys_public_counter_day (counter_name, owner_key, day)
    WHERE owner_key IS NOT NULL;
