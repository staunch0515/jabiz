-- Integrity seals (docs/design/21-audit-retention.md section 2; decision D27). Rows of the append-only tables are
-- sealed in blocks: each row's SHA-256 is kept in sys_integrity_item, each block sums its rows up in a Merkle root
-- and is linked to the block before by an HMAC chain, so that changes made past the application are found. The
-- tables are append-only themselves; their own rows are covered by the chain, not sealed as rows.

CREATE TABLE sys_integrity_seal (
    seal_no         bigint       PRIMARY KEY,
    sealed_time     timestamptz  NOT NULL,
    row_count       integer      NOT NULL,
    merkle_root     varchar(64)  NOT NULL,
    columns_hash    varchar(64)  NOT NULL,
    prev_hash       varchar(64)  NOT NULL,
    key_id          varchar(16)  NOT NULL,
    seal_hash       varchar(64)  NOT NULL,
    process_seq_id  bigint       REFERENCES op_process (process_seq_id)
);

-- One row per sealed row: which table, which key (the primary key as a JSON array), which digest, which block.
CREATE TABLE sys_integrity_item (
    table_name  varchar(63)  NOT NULL,
    row_key     text         NOT NULL,
    seal_no     bigint       NOT NULL REFERENCES sys_integrity_seal (seal_no),
    digest      varchar(64)  NOT NULL,
    PRIMARY KEY (table_name, row_key)
);
CREATE INDEX sys_integrity_item_seal_idx ON sys_integrity_item (seal_no, table_name);

-- The columns each table's rows of a block were digested over: a column added later is not part of the digests.
-- The lists are signed through the block's columns_hash.
CREATE TABLE sys_integrity_seal_table (
    seal_no     bigint       NOT NULL REFERENCES sys_integrity_seal (seal_no),
    table_name  varchar(63)  NOT NULL,
    columns     text[]       NOT NULL,
    PRIMARY KEY (seal_no, table_name)
);

-- Each verification and what it found (the first problems in full, the rest counted).
CREATE TABLE sys_integrity_check (
    check_no        bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    process_seq_id  bigint       REFERENCES op_process (process_seq_id),
    actor_id        varchar(100) NOT NULL,
    checked_time    timestamptz  NOT NULL,
    from_seal       bigint,
    to_seal         bigint,
    seal_count      integer      NOT NULL,
    row_count       bigint       NOT NULL,
    unsealed_count  bigint       NOT NULL,
    problem_count   integer      NOT NULL,
    problems        jsonb        NOT NULL,
    key_id          varchar(16)  NOT NULL
);
CREATE INDEX sys_integrity_check_time_idx ON sys_integrity_check (checked_time DESC);

SELECT jabiz_protect_append_only('sys_integrity_seal');
SELECT jabiz_protect_append_only('sys_integrity_item');
SELECT jabiz_protect_append_only('sys_integrity_seal_table');
SELECT jabiz_protect_append_only('sys_integrity_check');
