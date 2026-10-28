-- Requests to delete an account (docs/design/10-security.md section 17.5; decision D62 item 2). A request waits out the
-- cooling-off period; the daily job jabiz.account-deletion then runs ACCOUNT_ERASE for it. A request is pending while it
-- has no end. Both tables are append-only and hold no personal data: the user's id, times and who acted.

-- One request: by the user in the application (APP), confirmed from a mailed link (WEB), or scheduled by an
-- administrator (ADMIN). requested_by is the acting user's id (or the user's own for APP and WEB).
CREATE TABLE sys_account_deletion (
    request_id     uuid         PRIMARY KEY,
    user_id        uuid         NOT NULL,
    requested_time timestamptz  NOT NULL,
    due_time       timestamptz  NOT NULL,
    channel        varchar(10)  NOT NULL CHECK (channel IN ('APP', 'WEB', 'ADMIN')),
    requested_by   varchar(100) NOT NULL,
    process_seq_id bigint       NOT NULL REFERENCES op_process (process_seq_id)
);
CREATE INDEX sys_account_deletion_user_idx ON sys_account_deletion (user_id);
CREATE INDEX sys_account_deletion_due_idx ON sys_account_deletion (due_time);
SELECT jabiz_protect_append_only('sys_account_deletion');

-- How a request ended: withdrawn (CANCELLED, by the user or an administrator) or carried out (ERASED, with the erasure).
CREATE TABLE sys_account_deletion_end (
    request_id     uuid         PRIMARY KEY REFERENCES sys_account_deletion (request_id),
    ended_time     timestamptz  NOT NULL,
    outcome        varchar(10)  NOT NULL CHECK (outcome IN ('CANCELLED', 'ERASED')),
    erasure_id     uuid         REFERENCES sys_erasure (erasure_id),
    ended_by       varchar(100) NOT NULL,
    process_seq_id bigint       NOT NULL REFERENCES op_process (process_seq_id),
    CHECK ((outcome = 'ERASED') = (erasure_id IS NOT NULL))
);
SELECT jabiz_protect_append_only('sys_account_deletion_end');
