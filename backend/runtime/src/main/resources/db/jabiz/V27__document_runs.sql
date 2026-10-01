-- Issued documents (docs/design/22-documents.md section 4, decision D30): the PDF exactly as it was issued - printed
-- again and sent from these bytes, never laid out again - with its SHA-256, and what it shows as canonical JSON
-- (text, not jsonb: jsonb would reorder keys and drop the exact form the content hash is computed on), so that it can
-- be verified against the data. layout_source is the layout's canonical description, template_versions the version
-- of every template read. read_at and known_at are the point in time the templates were read at, which verification
-- reuses; scope keeps the issuer's values of the datasets whose scope depends on the caller. Append-only: a document
-- is never changed; a correction is a new document.
CREATE TABLE sys_document_run (
    run_id            uuid          PRIMARY KEY,
    layout_id         varchar(200)  NOT NULL,
    layout_version    char(64)      NOT NULL,
    layout_source     text          NOT NULL,
    template_versions text          NOT NULL,
    permissions       varchar(2000) NOT NULL,
    scope             text          NOT NULL,
    subject_entity    varchar(200),
    subject_id        varchar(200),
    document_no       varchar(200),
    title             varchar(500)  NOT NULL,
    language          varchar(10)   NOT NULL,
    page_size         varchar(10)   NOT NULL,
    params            text          NOT NULL,
    as_of             timestamptz,
    read_at           timestamptz   NOT NULL,
    known_at          timestamptz   NOT NULL,
    content           text          NOT NULL,
    content_hash      char(64)      NOT NULL,
    recomputable      boolean       NOT NULL,
    pdf               bytea         NOT NULL,
    pdf_hash          char(64)      NOT NULL,
    pdf_size          integer       NOT NULL CHECK (pdf_size > 0),
    page_count        integer       NOT NULL CHECK (page_count > 0),
    issued_by         varchar(100)  NOT NULL,
    issued_time       timestamptz   NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    version           bigint        NOT NULL
);
CREATE INDEX sys_document_run_layout_idx ON sys_document_run (layout_id, issued_time DESC);
CREATE INDEX sys_document_run_subject_idx ON sys_document_run (subject_entity, subject_id, issued_time DESC);
CREATE INDEX sys_document_run_process_idx ON sys_document_run (process_seq_id);
SELECT jabiz_protect_append_only('sys_document_run');
