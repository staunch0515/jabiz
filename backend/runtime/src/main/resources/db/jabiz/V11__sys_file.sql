-- Uploaded files (docs/design/14-files.md section 2; decision D18). An ordinary table, not append-only: a file is
-- really deleted when its owner asks; who uploaded or deleted which fileId stays in op_process. The storage key is
-- derived from file_id, so no path is stored. Columns that refer to a file (jabiz.file fields) have no foreign key
-- to this table: history may point at a deleted file.
CREATE TABLE sys_file (
    file_id       uuid           PRIMARY KEY,
    policy        varchar(100)   NOT NULL,
    content_type  varchar(100)   NOT NULL,
    size_bytes    numeric(19, 0) NOT NULL CHECK (size_bytes >= 0),
    sha256        varchar(64)    NOT NULL,
    width         numeric(9, 0),
    height        numeric(9, 0),
    variants      varchar(200),
    original_name varchar(255)   NOT NULL,
    uploaded_by   varchar(64)    NOT NULL,
    uploaded_time timestamptz    NOT NULL,
    version       bigint         NOT NULL
);
-- The sweep looks for old files first.
CREATE INDEX sys_file_uploaded_time_idx ON sys_file (uploaded_time);
