-- What a control change is about, as people name it (ROADMAP phase 17zn): the parameter's key, an approval rule's or
-- SoD rule's code, an approval limit's subject. Recorded at the proposal so that the list can show and filter it; older
-- changes have none (the table is append-only: they keep it in change_values).
ALTER TABLE sys_control_change_version ADD COLUMN target_key varchar(200);
CREATE INDEX sys_control_change_version_key_idx ON sys_control_change_version (target_key);
