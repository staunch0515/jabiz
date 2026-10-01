-- Files the server makes for a business process, such as a bank's payment file (docs/design/14-files.md section 10,
-- decision D31): kept exactly as made with their SHA-256 and handed out as these bytes only. permissions are what
-- reading one needs besides file.generated.read; subject names what it was made for. Append-only: a file is never
-- changed; one made in error is superseded by the business process, never deleted here.
CREATE TABLE sys_generated_file (
    file_id        uuid          PRIMARY KEY,
    file_name      varchar(200)  NOT NULL,
    media_type     varchar(100)  NOT NULL,
    content        bytea         NOT NULL,
    sha256         char(64)      NOT NULL,
    size           integer       NOT NULL CHECK (size > 0),
    permissions    varchar(2000) NOT NULL,
    subject_entity varchar(100),
    subject_id     varchar(100),
    created_by     varchar(100)  NOT NULL,
    created_time   timestamptz   NOT NULL,
    process_seq_id bigint        NOT NULL REFERENCES op_process (process_seq_id),
    version        bigint        NOT NULL
);
CREATE INDEX sys_generated_file_subject_idx ON sys_generated_file (subject_entity, subject_id, created_time DESC);
CREATE INDEX sys_generated_file_process_idx ON sys_generated_file (process_seq_id);
SELECT jabiz_protect_append_only('sys_generated_file');

-- Every download of a generated file is recorded as the values it carries leaving in plain text.
ALTER TABLE sys_reveal_record DROP CONSTRAINT sys_reveal_record_kind_check;
ALTER TABLE sys_reveal_record ADD CONSTRAINT sys_reveal_record_kind_check
    CHECK (kind IN ('VALUE', 'QUERY', 'EXPORT', 'FILE'));
