-- Retention fixtures (docs/design/21-audit-retention.md section 3; see ItRetentionFixtures): a plain entity kept
-- seven years from the end of the fiscal year of its booking, and a temporal one kept a year from its issue.

CREATE TABLE it_record (
    f_id        varchar(64)  PRIMARY KEY,
    f_title     text         NOT NULL,
    f_vendor    varchar(20),
    f_booked_at timestamptz,
    f_version   bigint       NOT NULL DEFAULT 1
);

CREATE TABLE it_document (
    row_id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id       uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer      NOT NULL,
    effect_start_time timestamptz  NOT NULL,
    created_time      timestamptz  NOT NULL,
    process_seq_id    bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean      NOT NULL DEFAULT false,
    title             varchar(200) NOT NULL,
    vendor            varchar(20),
    issued_time       timestamptz,
    CONSTRAINT it_document_version_uk UNIQUE (document_id, version_no)
);
CREATE INDEX it_document_current_idx ON it_document (document_id, effect_start_time DESC, version_no DESC);
CREATE INDEX it_document_process_idx ON it_document (process_seq_id);
SELECT jabiz_protect_append_only('it_document');
