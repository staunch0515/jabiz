-- An ordinary table a test process fills with thousands of rows in one commit (ROADMAP 17zz14, BulkWriteIT).
CREATE TABLE it_bulk_row (
    f_id       varchar(64)  PRIMARY KEY,
    f_name     varchar(100) NOT NULL,
    f_n        numeric(9,0) NOT NULL,
    f_version  bigint       NOT NULL DEFAULT 1
);
