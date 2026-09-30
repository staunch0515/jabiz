-- Plain-text display of masked fields, data periods, access reviews (docs/design/10-security.md section 13,
-- decision D28 items 7-9).

-- Every time a masked value left the platform in plain text: one value on request (VALUE), or the plain columns of
-- a template run or report export (QUERY) or of a data export (EXPORT) by a holder of the permission. Only inserted;
-- sealed like every append-only table (21 section 2).
CREATE TABLE sys_reveal_record (
    reveal_id   uuid          PRIMARY KEY,
    revealed_at timestamptz   NOT NULL,
    actor_id    varchar(100)  NOT NULL,
    request_id  varchar(100)  NOT NULL,
    kind        varchar(10)   NOT NULL CHECK (kind IN ('VALUE', 'QUERY', 'EXPORT')),
    resource    varchar(200)  NOT NULL,
    entity      varchar(100)  NOT NULL,
    entity_id   varchar(100),
    fields      varchar(2000) NOT NULL,
    row_count   bigint        CHECK (row_count >= 0)
);
CREATE INDEX sys_reveal_record_time_idx ON sys_reveal_record (revealed_at);
CREATE INDEX sys_reveal_record_entity_idx ON sys_reveal_record (entity, entity_id);
SELECT jabiz_protect_append_only('sys_reveal_record');

-- A role assignment may be limited to the data of a span of business time: [data_from, data_to).
ALTER TABLE sec_user_role_version ADD COLUMN data_from timestamptz;
ALTER TABLE sec_user_role_version ADD COLUMN data_to timestamptz;
ALTER TABLE sec_user_role_version ADD CONSTRAINT sec_user_role_version_data_period_ck
    CHECK (data_from IS NULL OR data_to IS NULL OR data_from < data_to);

-- A signed access review: the issued access report (REPORT_ISSUE) and its content hash as of the end of the period,
-- the security changes of the period from the audit trail (count and hash), the segregation-of-duties conflicts at
-- signing, who signed and their comment. Only inserted.
CREATE TABLE sys_access_review (
    review_id       uuid          PRIMARY KEY,
    period_from     timestamptz   NOT NULL,
    period_to       timestamptz   NOT NULL,
    report_run_id   uuid          NOT NULL REFERENCES sys_report_run (run_id),
    report_hash     char(64)      NOT NULL,
    changes_count   integer       NOT NULL CHECK (changes_count >= 0),
    changes_hash    char(64)      NOT NULL,
    conflicts       text          NOT NULL,
    conflicts_count integer       NOT NULL CHECK (conflicts_count >= 0),
    conflicts_hash  char(64)      NOT NULL,
    reviewer        varchar(100)  NOT NULL,
    review_comment  varchar(2000) NOT NULL,
    signed_at       timestamptz   NOT NULL,
    process_seq_id  bigint        NOT NULL REFERENCES op_process (process_seq_id),
    CONSTRAINT sys_access_review_period_ck CHECK (period_from < period_to)
);
CREATE INDEX sys_access_review_period_idx ON sys_access_review (period_to DESC);
SELECT jabiz_protect_append_only('sys_access_review');
