-- Files by uploader (docs/design/14-files.md section 2; ROADMAP 17zq). An application that counts or sums one user's
-- files (storage used per uploader) and the default list view of SysFile (filter uploadedBy, default sort
-- uploadedTime descending) both select by uploaded_by; without an index each of them reads the whole table.
-- (uploaded_by, uploaded_time) serves both: the equality on the first column, and the order (either direction) or a
-- time range on the second. The orphan sweep pages by (uploaded_time, file_id) without an uploader and keeps
-- sys_file_uploaded_time_idx; no other platform query filters by uploader, so one index is enough.
-- Plain CREATE INDEX: platform migrations run in Flyway's transaction (no CONCURRENTLY anywhere).
CREATE INDEX IF NOT EXISTS sys_file_uploaded_by_idx ON sys_file (uploaded_by, uploaded_time);
