-- Account deletion and the erasure of personal data (docs/design/10-security.md section 17; decision D62).

-- When the user's account was erased: written by ACCOUNT_ERASE only. Such a user is disabled and stays so; its name,
-- addresses and password are gone (cleared by a version, then overwritten in every version by ERASURE_PURGE).
ALTER TABLE sec_user_version ADD COLUMN erased_at timestamptz;

-- One erasure of one user, written by ACCOUNT_ERASE in the transaction that disabled the account and ran the
-- application's erasers. ERASURE_PURGE then overwrites the personal columns of the append-only rows.
CREATE TABLE sys_erasure (
    erasure_id     uuid         PRIMARY KEY,
    user_id        uuid         NOT NULL,
    erased_time    timestamptz  NOT NULL,
    requested_by   varchar(100) NOT NULL,
    process_seq_id bigint       NOT NULL REFERENCES op_process (process_seq_id)
);
CREATE INDEX sys_erasure_user_idx ON sys_erasure (user_id);
SELECT jabiz_protect_append_only('sys_erasure');

-- The purge of an erasure, written in the purge's own transaction on the maintenance connection (decision D62 item 3).
-- Its operation (process_seq_id) is recorded on the application's connection and commits after it, so there is no
-- foreign key to op_process: a purge whose operation did not commit is complete all the same.
CREATE TABLE sys_erasure_purge (
    erasure_id     uuid         PRIMARY KEY REFERENCES sys_erasure (erasure_id),
    purged_time    timestamptz  NOT NULL,
    row_count      integer      NOT NULL,
    process_seq_id bigint       NOT NULL,
    -- HMAC-SHA256 with the integrity key over the erasure, its row count and its entries (ErasureEvidence): the
    -- digests in the entries are plain SHA-256, and only a signed purge's entries account for a changed row.
    signature      char(64)     NOT NULL,
    key_id         varchar(16)  NOT NULL
);
SELECT jabiz_protect_append_only('sys_erasure_purge');

-- Each append-only row the purge overwrote: which columns, and the row's digest before and after over the columns
-- of the block that sealed it (none when it was not sealed yet). The verification accepts a sealed row whose digest
-- leads from the sealed one to the current one through these entries (ERASED); these rows are sealed in turn.
CREATE TABLE sys_erasure_item (
    erasure_id     uuid         NOT NULL REFERENCES sys_erasure (erasure_id),
    table_name     varchar(100) NOT NULL,
    row_key        text         NOT NULL,
    columns        text[]       NOT NULL,
    digest_before  char(64),
    digest_after   char(64),
    PRIMARY KEY (erasure_id, table_name, row_key)
);
CREATE INDEX sys_erasure_item_row_idx ON sys_erasure_item (table_name, row_key);
SELECT jabiz_protect_append_only('sys_erasure_item');


-- How many sealed rows a verification found overwritten by a purge and accounted for by its entries (decision D62
-- item 6): not problems, counted.
ALTER TABLE sys_integrity_check ADD COLUMN erased_count integer NOT NULL DEFAULT 0;
